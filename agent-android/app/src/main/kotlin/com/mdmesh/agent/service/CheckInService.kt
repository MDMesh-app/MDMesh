package com.mdmesh.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mdmesh.agent.R
import com.mdmesh.core.power.PowerModeStore
import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.sync.CheckInCoordinator
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.core.transport.TransportManager
import com.mdmesh.core.transport.WakeSignal
import com.mdmesh.proto.EventType
import dagger.hilt.android.AndroidEntryPoint
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

    @Volatile private var started = false
    @Volatile private var interactiveUntil = 0L
    @Volatile private var deviceId: String? = null
    @Volatile private var secret: String? = null

    @Suppress("DEPRECATION")
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                android.net.ConnectivityManager.CONNECTIVITY_ACTION ->
                    runCatching { eventLog.record(EventType.CONNECTIVITY) }
                Intent.ACTION_BATTERY_LOW ->
                    runCatching { eventLog.record(EventType.LOW_BATTERY) }
            }
            reevaluateSocket()
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        ContextCompat.registerReceiver(this, powerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startAsForeground()
        if (!started) {
            started = true
            lifecycleScope.launch {
                deviceId = identity.current()
                secret = identity.secret()
                runCatching { coordinator.runOnce() } // initial sync
                reevaluateSocket()
            }
        }
        return START_STICKY
    }

    /** Hold the socket when always-on, screen-on, or charging; otherwise drop it (heartbeat covers idle). */
    private fun reevaluateSocket() {
        val id = deviceId
        val sec = secret
        if (id.isNullOrBlank() || sec.isNullOrBlank()) return
        val hot = powerModeStore.isAlwaysOn() || isInteractive() || isCharging()
        if (hot) {
            transport.start(id, sec) { signal -> onWake(signal) }
        } else {
            transport.stop()
        }
    }

    private suspend fun onWake(signal: WakeSignal) {
        when (signal.kind) {
            "interactive" -> {
                interactiveUntil = System.currentTimeMillis() + (signal.ttlSec ?: 120) * 1000L
                fastSyncLoop()
            }
            else -> runCatching { coordinator.runOnce() }
        }
        reevaluateSocket() // a device.powerMode command may have just changed the mode
    }

    private suspend fun fastSyncLoop() {
        while (System.currentTimeMillis() < interactiveUntil) {
            runCatching { coordinator.runOnce() }
            delay(2_500L)
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
        transport.stop()
        super.onDestroy()
    }

    private fun startAsForeground() {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.checkin_notification_title))
            .setContentText(getString(R.string.checkin_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        private const val CHANNEL_ID = "mdm_checkin"
        private const val NOTIFICATION_ID = 1001
    }
}
