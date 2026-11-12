package com.mdmesh.agent

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mdmesh.agent.service.CheckInService
import com.mdmesh.core.action.NotificationPermissionAccess
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.config.ConfigApplier
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.store.configuredKiosk
import com.mdmesh.core.store.DeviceIdStore
import com.mdmesh.core.store.EnrollTokenStore
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.sync.CheckInWorker
import com.mdmesh.core.sync.LastCheckIn
import com.mdmesh.core.sync.SyncStatus
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.policy.wifi.DpmHandle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * MDMesh agent home / status screen. Doubles as the kiosk HOME surface
 * (`android:lockTaskMode="if_whitelisted"` + HOME intent filter), so when the agent's
 * package is lock-task-allowlisted the system auto-enters lock task here.
 *
 * Shows the real managed state — Device-Owner status, server-issued device id, kiosk state,
 * and the server URL — so a person looking at the device can see it's managed by MDMesh.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deviceIdStore: DeviceIdStore
    @Inject lateinit var dpmHandle: DpmHandle
    @Inject lateinit var notificationPermission: NotificationPermissionAccess
    @Inject lateinit var serverConfig: ServerConfigStore
    @Inject lateinit var syncStatus: SyncStatus
    @Inject lateinit var configApplier: ConfigApplier
    @Inject lateinit var configStore: ConfigStateStore
    @Inject lateinit var kioskStore: KioskStateStore
    @Inject lateinit var events: EventLog
    @Inject lateinit var enrollTokens: EnrollTokenStore
    @Inject lateinit var lastCheckIn: LastCheckIn

    private lateinit var deviceIdValue: TextView
    private lateinit var credentialsLostValue: TextView
    private lateinit var kioskValue: TextView
    private lateinit var returnButton: Button
    private var returningToKiosk = false
    private var confirmDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(configStore.configuredKioskFlow(), kioskStore.flow()) { _, _ -> Unit }
                    .collect { refresh() }
            }
        }
        // The DEVICE ID area follows the stored state while the screen is visible: a background recovery that marks
        // the credentials lost, a check-in that succeeds, an enrollment. Nothing is collected while stopped.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DeviceIdLabel.watch(
                    deviceIds = deviceIdStore.deviceId,
                    marks = enrollTokens.credentialsLostMarks,
                    lastSuccess = lastCheckIn.okAtMsChanges,
                    lastError = syncStatus.lastError,
                ).collect { show(it) }
            }
        }
        // Grant our own POST_NOTIFICATIONS as Device Owner BEFORE starting the foreground
        // service, so the "MDMesh active" notification is visible on Android 13+.
        notificationPermission.ensure()
        // NOTE: we intentionally do NOT pop the battery-optimization request dialog here. As a
        // foreground-service Device Owner we survive Doze well enough, and that full-screen system
        // dialog used to sit on top and pause the kiosk launcher, so a kiosk.enter wouldn't take
        // visible effect until the user dismissed it. There is no public Device-Owner API to grant
        // the Doze exemption silently, so the safest behaviour is to not interrupt.
        // Start the near-real-time command channel (foreground poll loop). Starting from the
        // launcher activity keeps us in the foreground-start allowance on Android 12+.
        ContextCompat.startForegroundService(this, Intent(this, CheckInService::class.java))
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        confirmDialog?.dismiss()
        confirmDialog = null
        super.onDestroy()
    }

    private fun refresh() {
        kioskValue.text = if (isLocked()) "Locked (kiosk active)" else "Not locked"
        val canReturn = isDeviceOwner() && !isLocked() && configStore.configuredKiosk() != null
        returnButton.visibility = if (canReturn) View.VISIBLE else View.GONE
        returnButton.isEnabled = canReturn && !returningToKiosk
        returnButton.text = if (returningToKiosk) "Returning…" else "Return to kiosk"
    }

    /** The DEVICE ID area: the id (mono, alone, so it can be copied as it is) and, under it, any warning. */
    private fun show(status: DeviceIdLabel.Status) {
        when (status) {
            is DeviceIdLabel.Status.Enrolling -> {
                val failure = status.lastError
                deviceIdValue.text = if (failure == null) {
                    getString(R.string.status_enrolling)
                } else {
                    val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(failure.atMillis))
                    getString(R.string.status_enrolling_error, failure.message, at)
                }
                credentialsLostValue.visibility = View.GONE
            }
            is DeviceIdLabel.Status.Enrolled -> {
                deviceIdValue.text = status.deviceId
                credentialsLostValue.visibility = if (status.credentialsLost) View.VISIBLE else View.GONE
            }
        }
    }

    /** Ask first: returning locks the device, and some kiosks have no exit on the device at all. */
    private fun confirmReturnToKiosk() {
        if (returningToKiosk || confirmDialog != null) return
        val kiosk = configStore.configuredKiosk() ?: return refresh()
        val prompt = KioskReturnPrompt.of(kiosk)
        confirmDialog = AlertDialog.Builder(this)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(prompt.confirm) { _, _ -> returnToKiosk(kiosk) }
            .setOnDismissListener { confirmDialog = null }
            .show()
    }

    private fun returnToKiosk(confirmed: KioskApplyPayload) {
        if (returningToKiosk) return
        returningToKiosk = true
        refresh()
        lifecycleScope.launch {
            try {
                // Not cancelled with the activity (a rotation, say): stopping mid-entry could leave a partial kiosk,
                // HOME claimed and the allowlist set with nothing saved. The entry, its event and the check-in finish.
                val error = withContext(NonCancellable) { enterConfiguredKiosk(confirmed) }
                error?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            } finally {
                returningToKiosk = false
                refresh()
            }
        }
    }

    /** Enters the confirmed kiosk; null when it worked, else what to tell the person. */
    private suspend fun enterConfiguredKiosk(confirmed: KioskApplyPayload): String? {
        val result = runCatching { configApplier.returnToConfiguredKiosk(expected = confirmed) }.getOrElse {
            if (it is CancellationException) throw it
            KioskResult.Failed("Could not restore kiosk. Please try again.")
        }
        return when (result) {
            KioskResult.Ok -> {
                events.record("kioskReturn", "returned on-device")
                // Tell the server now, so the console shows the device in kiosk without waiting for the next
                // heartbeat. After any check-in already running: that one may carry the state from before.
                CheckInWorker.scheduleAfterCurrent(applicationContext)
                null
            }
            KioskResult.Unsupported -> "Kiosk is not supported on this device."
            is KioskResult.Failed -> result.reason
        }
    }

    private fun isLocked(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
    }

    private fun isDeviceOwner(): Boolean =
        dpmHandle.dpm.isDeviceOwnerApp(packageName)

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(INK)
            setPadding(dp(28), dp(40), dp(28), dp(40))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        root.addView(text("MDMesh", 30f, SIGNAL, bold = true))
        root.addView(text("Device agent", 14f, MUTED).apply { setPadding(0, dp(2), 0, dp(20)) })

        root.addView(label("MANAGEMENT"))
        root.addView(
            text(
                if (isDeviceOwner()) "Device Owner — active" else "Not managed",
                16f,
                if (isDeviceOwner()) OK else ALERT,
            ),
        )
        root.addView(spacer())

        root.addView(label("DEVICE ID"))
        deviceIdValue = text("…", 14f, TEXT, mono = true)
        root.addView(deviceIdValue)
        // Its own view, in the body font: a warning to read, announced when it appears, never part of the id.
        credentialsLostValue = text(getString(R.string.status_credentials_lost), 14f, ALERT).apply {
            visibility = View.GONE
            setPadding(0, dp(WARNING_GAP), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(credentialsLostValue)
        root.addView(spacer())

        root.addView(label("KIOSK"))
        kioskValue = text("…", 16f, TEXT)
        root.addView(kioskValue)
        returnButton = kioskReturnButton()
        root.addView(returnButton)
        root.addView(spacer())

        root.addView(label("AGENT VERSION"))
        root.addView(
            text(
                "${com.mdmesh.agent.BuildConfig.VERSION_NAME} (build ${com.mdmesh.agent.BuildConfig.VERSION_CODE})",
                15f,
                TEXT,
                mono = true,
            ),
        )
        root.addView(spacer())

        root.addView(label("SERVER"))
        root.addView(text(serverConfig.baseUrl(), 13f, MUTED, mono = true))
        root.addView(spacer())

        root.addView(
            text("Managed by MDMesh", 12f, MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, 0)
            },
        )

        return ScrollView(this).apply {
            setBackgroundColor(INK)
            addView(root)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
    }

    private fun kioskReturnButton(): Button = Button(this).apply {
        visibility = View.GONE
        isAllCaps = false
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(FAINT, SIGNAL),
        )
        setTextColor(INK)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(RETURN_BUTTON_GAP) }
        setOnClickListener { confirmReturnToKiosk() }
    }

    private fun label(s: String): TextView =
        text(s, 11f, FAINT).apply {
            letterSpacing = 0.10f
            setPadding(0, 0, 0, dp(4))
        }

    private fun spacer(): TextView = TextView(this).apply {
        height = dp(16)
    }

    private fun text(
        s: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val RETURN_BUTTON_GAP = 12
        const val WARNING_GAP = 6
        const val INK = MainActivityColors.INK
        const val TEXT = MainActivityColors.TEXT
        const val MUTED = MainActivityColors.MUTED
        const val FAINT = MainActivityColors.FAINT
        const val SIGNAL = MainActivityColors.SIGNAL
        const val OK = MainActivityColors.OK
        const val ALERT = MainActivityColors.ALERT
    }
}
