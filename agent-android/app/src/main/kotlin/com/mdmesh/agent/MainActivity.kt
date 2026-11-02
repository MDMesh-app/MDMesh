package com.mdmesh.agent

import android.app.ActivityManager
import android.graphics.Color
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
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.sync.SyncStatus
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.wifi.DpmHandle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
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

    private lateinit var deviceIdValue: TextView
    private lateinit var kioskValue: TextView
    private lateinit var returnButton: Button
    private var returningToKiosk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(configStore.configuredKioskFlow(), kioskStore.flow()) { _, _ -> Unit }
                    .collect { refresh() }
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

    private fun refresh() {
        kioskValue.text = if (isLocked()) "Locked (kiosk active)" else "Not locked"
        val canReturn = isDeviceOwner() && !isLocked() && configStore.configuredKiosk() != null
        returnButton.visibility = if (canReturn) View.VISIBLE else View.GONE
        returnButton.isEnabled = canReturn && !returningToKiosk
        returnButton.text = if (returningToKiosk) "Returning…" else "Return to kiosk"
        lifecycleScope.launch {
            val id = deviceIdStore.current()
            deviceIdValue.text = if (id.isNullOrBlank()) enrollingLabel() else id
        }
    }

    private fun returnToKiosk() {
        if (returningToKiosk) return
        returningToKiosk = true
        refresh()
        lifecycleScope.launch {
            try {
                val result = runCatching { configApplier.returnToConfiguredKiosk() }.getOrElse {
                    if (it is CancellationException) throw it
                    KioskResult.Failed("Could not restore kiosk. Please try again.")
                }
                val error = when (result) {
                    KioskResult.Ok -> null
                    KioskResult.Unsupported -> "Kiosk is not supported on this device."
                    is KioskResult.Failed -> result.reason
                }
                error?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            } finally {
                returningToKiosk = false
                refresh()
            }
        }
    }

    /** "Enrolling…" plus the last sync error (if any), so a stuck enrollment is diagnosable on-device. */
    private fun enrollingLabel(): String {
        val failure = syncStatus.lastError.value ?: return "Enrolling…"
        val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(failure.atMillis))
        return "Enrolling… (last error: ${failure.message} at $at)"
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
        root.addView(spacer())

        root.addView(label("KIOSK"))
        kioskValue = text("…", 16f, TEXT)
        root.addView(kioskValue)
        returnButton = Button(this, null, 0, R.style.Widget_MDMesh_KioskReturnButton).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(RETURN_BUTTON_GAP) }
            setOnClickListener { returnToKiosk() }
        }
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
        val INK = Color.parseColor("#0E1117")
        val TEXT = Color.parseColor("#E8EEF4")
        val MUTED = Color.parseColor("#8693A4")
        val FAINT = Color.parseColor("#5C6675")
        val SIGNAL = Color.parseColor("#F4B942")
        val OK = Color.parseColor("#3FD08A")
        val ALERT = Color.parseColor("#F2545B")
    }
}
