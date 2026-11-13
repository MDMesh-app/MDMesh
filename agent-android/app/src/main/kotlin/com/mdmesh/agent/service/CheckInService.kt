package com.mdmesh.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mdmesh.agent.R
import com.mdmesh.core.action.NotificationPermissionAccess
import com.mdmesh.core.install.InstallManager
import com.mdmesh.core.power.PowerModeStore
import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.sync.CheckInCoordinator
import com.mdmesh.core.sync.CredentialsFollower
import com.mdmesh.core.sync.SyncStatus
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.core.transport.TransportManager
import com.mdmesh.core.transport.WakeSignal
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that owns the **wake-to-sync** command channel.
 *
 * Power modes (see [PowerModeStore]):
 *  - **adaptive** (default, battery-saving): hold the WebSocket only while the screen is on or the
 *    device is charging; when idle on battery, drop the socket and rely on the cheap doze-proof
 *    heartbeat ([WakeKeepAlive]) for reconcile. Instant when you're using it, frugal when pocketed.
 *  - **alwaysOn**: keep the socket hot 24/7 for constant instant connectivity (higher battery cost).
 *
 * The socket decision is re-evaluated on screen/power changes and after each check-in (so a
 * `device.powerMode` command takes effect promptly).
 */
@AndroidEntryPoint
class CheckInService : LifecycleService() {

    @Inject lateinit var coordinator: CheckInCoordinator
    @Inject lateinit var transport: TransportManager
    @Inject lateinit var identity: DeviceIdentity
    @Inject lateinit var powerModeStore: PowerModeStore
    @Inject lateinit var eventLog: EventLog
    @Inject lateinit var notificationPermission: NotificationPermissionAccess
    @Inject lateinit var installManager: InstallManager
    @Inject lateinit var syncStatus: SyncStatus

    @Volatile private var started = false
    @Volatile private var interactiveUntil = 0L
    /** The credentials the wake socket dials with; built in onCreate, before any start reads the identity. */
    private lateinit var wake: CredentialsFollower
    @Volatile private var fastSyncJob: Job? = null
    // Reachability grace: hold the socket for a window after service start even on idle
    // battery, so a device that just booted / self-updated is instantly commandable — a reboot
    // usually means an operator is acting on it. After the window, adaptive gating resumes.
    // Not when the service is revived after Android stopped it (see StartPlan.of).
    @Volatile private var graceUntil = 0L

    @Suppress("DEPRECATION")
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            DeviceEvents.eventFor(intent?.action, isInitialStickyBroadcast)?.let { event ->
                runCatching { eventLog.record(event) }
            }
            wake.reevaluate() // also for the initial sticky one: brings the socket up as soon as the service starts
        }
    }

    // A device that gets its network back reconnects the wake socket now instead of sitting out its backoff.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = transport.onNetworkAvailable()
    }

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        // First, as the receivers below use it. For the service's lifetime, a check-in that saves new credentials (the
        // one that enrolls a device a start found unenrolled, or one that recovers the secret the server holds) moves
        // the wake socket onto them at once.
        wake = CredentialsFollower(lifecycleScope, syncStatus.successes, identity, transport, ::socketWanted, ::onWake)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        ContextCompat.registerReceiver(this, powerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Best effort: the system caps callbacks per app (API 30+ throws past it); the backoff still retries.
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "network callback not registered", it) }
        // An APK a dead process left behind would otherwise wait for the next install.
        lifecycleScope.launch { installManager.sweepLeftoverDownloads() }
    }

    private val connectivity: ConnectivityManager
        get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startAsForeground()
        val first = !started
        started = true
        // Also when started again without credentials: the first sync can run before provisioning has saved the
        // enroll token, and then it is retried now rather than at the next cycle.
        val plan = StartPlan.of(
            first = first,
            starter = StartPlan.Starter.of(
                hasIntent = intent != null,
                fromHeartbeat = intent?.getBooleanExtra(EXTRA_FROM_HEARTBEAT, false) == true,
            ),
            hasCredentials = wake.current != null,
        )
        if (plan.grace) {
            graceUntil = System.currentTimeMillis() + REACHABILITY_GRACE_MS
            lifecycleScope.launch {
                delay(REACHABILITY_GRACE_MS + 1_000L)
                wake.reevaluate() // drop back to adaptive gating once the grace window lapses
            }
        }
        if (plan.loadCredentials) {
            lifecycleScope.launch {
                if (plan.initialSync) {
                    runCatching { coordinator.runOnce() } // initial sync
                        .onFailure { Log.w(TAG, "initial check-in failed", it) }
                }
                // Read identity AFTER the initial sync: on a fresh device that runOnce just
                // enrolled, so reading before it would leave blank credentials and no socket
                // until the process restarts.
                wake.load()
                // Not enrolled yet (e.g. revived before the first enrollment, so no check-in of its own): the
                // credentials follower built in onCreate picks them up as soon as a check-in saves them.
                wake.reevaluate()
            }
        }
        return START_STICKY
    }

    /** Hold the socket when always-on, screen-on, or charging; otherwise drop it (heartbeat covers idle). */
    private fun socketWanted(): Boolean =
        powerModeStore.isAlwaysOn() || isInteractive() || isCharging() || System.currentTimeMillis() < graceUntil

    private suspend fun onWake(signal: WakeSignal) {
        when (signal.kind) {
            "interactive" -> {
                interactiveUntil = System.currentTimeMillis() + (signal.ttlSec ?: 120) * 1000L
                startFastSync()
            }
            else -> runCatching { coordinator.runOnce() }
                .onFailure { Log.w(TAG, "wake check-in failed", it) }
        }
        wake.reevaluate() // a device.powerMode command may have just changed the mode
    }

    /** Start the 2.5s fast-sync loop unless one is already running — repeat "interactive"
     *  signals only extend [interactiveUntil], they must not stack extra loops. */
    @Synchronized
    private fun startFastSync() {
        if (fastSyncJob?.isActive == true) return
        fastSyncJob = lifecycleScope.launch {
            var consecutiveFailures = 0
            while (System.currentTimeMillis() < interactiveUntil) {
                runCatching { coordinator.runOnce() }
                    .onSuccess { consecutiveFailures = 0 }
                    .onFailure {
                        Log.w(TAG, "fast-sync check-in failed", it)
                        // Server unreachable — stop burning battery; the periodic floor covers.
                        if (++consecutiveFailures >= 3) return@launch
                    }
                delay(2_500L)
            }
        }
    }

    private fun isInteractive(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun isCharging(): Boolean {
        val batt = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batt?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(powerReceiver) }
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        transport.stop()
        super.onDestroy()
    }

    private fun startAsForeground() {
        // Every start path (launcher, kiosk, boot, provisioning) comes through here, so the "MDMesh active"
        // notification shows on Android 13+ even when the agent was enrolled by QR and its screen never opened.
        notificationPermission.ensure()
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.checkin_notification_title))
            .setContentText(getString(R.string.checkin_notification_text))
            .setSmallIcon(R.drawable.ic_stat_mdmesh)
            .setColor(ContextCompat.getColor(this, R.color.mdm_notification_ink))
            .setOngoing(true)
            .build()

        // specialUse on 34+ (allowed from BOOT_COMPLETED, unlike dataSync on Android 15);
        // dataSync on 29..33 where specialUse doesn't exist and boot starts are unrestricted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.checkin_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        /**
         * Start the instant command channel from outside the app's screens (provisioning). A Device Owner may start a
         * foreground service from the background; if an OEM refuses anyway, the scheduled check-ins still run.
         */
        fun start(context: Context, fromHeartbeat: Boolean = false) {
            val intent = Intent(context, CheckInService::class.java).putExtra(EXTRA_FROM_HEARTBEAT, fromHeartbeat)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** Started by the heartbeat to revive the service: see [StartPlan.of]. */
        private const val EXTRA_FROM_HEARTBEAT = "com.mdmesh.agent.extra.FROM_HEARTBEAT"

        private const val TAG = "CheckInService"
        private const val CHANNEL_ID = "mdm_checkin"
        private const val NOTIFICATION_ID = 1001

        /** Post-(re)start window during which the socket is held regardless of power mode. */
        private const val REACHABILITY_GRACE_MS = 10L * 60L * 1000L
    }
}
