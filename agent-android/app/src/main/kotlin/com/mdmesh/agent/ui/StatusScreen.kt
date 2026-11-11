package com.mdmesh.agent.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.mdmesh.agent.BuildConfig
import com.mdmesh.agent.R
import java.text.DateFormat
import java.util.Date

/**
 * The status screen's views (res/layout/activity_status.xml). [render] draws a whole [StatusUiState]; it is the
 * only code that changes these views after setup. [onReturn] is the Return to kiosk button.
 */
class StatusScreen(private val activity: Activity, onReturn: () -> Unit) {

    private val deviceId: TextView = activity.findViewById(R.id.status_device_id)
    private val server: TextView = activity.findViewById(R.id.status_server)
    private val management = Callout(activity.findViewById(R.id.status_management))
    private val enrollError = Callout(activity.findViewById(R.id.status_enroll_error))
    private val kioskPill: TextView = activity.findViewById(R.id.status_kiosk_pill)
    private val kioskBody: TextView = activity.findViewById(R.id.status_kiosk_body)
    private val returnButton: Button = activity.findViewById(R.id.status_return)
    private val returnError = Callout(activity.findViewById(R.id.status_return_error))
    private var drawn: StatusUiState? = null

    init {
        // The screen colour runs under the bars: the window's background (so it isn't drawn twice), and the bars'
        // own colour before Android 15.
        val background = color(R.color.mdm_app)
        activity.window.setBackgroundDrawable(ColorDrawable(background))
        activity.colorSystemBars(background)
        SystemBars.fit(activity, activity.findViewById(R.id.status_scroll), background)
        // On a tablet or in landscape the column stops at a readable width, centred.
        val column = activity.findViewById<View>(R.id.status_column)
        val maxWidth = activity.resources.getDimensionPixelSize(R.dimen.mdm_content_max_width)
        if (activity.resources.displayMetrics.widthPixels > maxWidth) {
            column.layoutParams = (column.layoutParams as FrameLayout.LayoutParams).apply { width = maxWidth }
        }
        Wordmark.bind(
            activity.findViewById(R.id.status_wordmark_mark),
            activity.findViewById(R.id.status_wordmark_text),
            activity.getString(R.string.brand_name),
            ink = color(R.color.mdm_ink),
            secondary = color(R.color.mdm_secondary),
        )
        activity.findViewById<TextView>(R.id.status_version).text =
            activity.getString(R.string.status_agent_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        returnButton.setOnClickListener { onReturn() }
    }

    fun render(state: StatusUiState) {
        val before = drawn
        drawn = state
        if (before?.deviceOwner != state.deviceOwner) renderManagement(state.deviceOwner)
        if (before?.deviceId != state.deviceId) renderDeviceId(state.deviceId)
        if (before?.serverUrl != state.serverUrl) server.text = state.serverUrl
        if (before?.kiosk != state.kiosk) renderKiosk(state.kiosk)
        if (state.returnFailure == null) {
            returnError.hide()
        } else {
            val title = activity.getString(R.string.kiosk_return_failed_title)
            returnError.show(CalloutTone.ALERT, title, state.returnFailure)
        }
    }

    private fun renderManagement(deviceOwner: Boolean) {
        if (deviceOwner) {
            management.show(
                CalloutTone.OK,
                activity.getString(R.string.status_managed_by),
                activity.getString(R.string.status_device_owner_active),
            )
        } else {
            management.show(
                CalloutTone.ALERT,
                activity.getString(R.string.status_not_managed),
                activity.getString(R.string.status_not_managed_body),
            )
        }
    }

    /** "Enrolling…" plus the last sync error (if any), so a stuck enrollment is diagnosable on-device. */
    private fun renderDeviceId(id: StatusUiState.DeviceIdUi) {
        when (id) {
            StatusUiState.DeviceIdUi.Loading -> deviceId.setText(R.string.status_placeholder)
            is StatusUiState.DeviceIdUi.Known -> deviceId.text = id.id
            is StatusUiState.DeviceIdUi.Enrolling -> deviceId.setText(R.string.status_enrolling)
        }
        val error = (id as? StatusUiState.DeviceIdUi.Enrolling)?.lastError
        if (error == null) {
            enrollError.hide()
        } else {
            val at = id.lastErrorAtMs?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }.orEmpty()
            enrollError.show(
                CalloutTone.WARN,
                activity.getString(R.string.status_enrolling_error_title),
                activity.getString(R.string.status_enrolling_error, error, at),
            )
        }
    }

    private fun renderKiosk(ui: KioskCardUi) {
        // Status pill beside "Kiosk": ok tone while the kiosk runs, neutral otherwise.
        kioskPill.setText(ui.pill)
        kioskPill.setTextColor(color(if (ui.active) R.color.mdm_ok_ink else R.color.mdm_secondary))
        kioskPill.keepingPadding {
            kioskPill.setBackgroundResource(if (ui.active) R.drawable.mdm_pill_on else R.drawable.mdm_pill_off)
        }
        val dot = if (ui.active) R.drawable.mdm_dot_on else R.drawable.mdm_dot_off
        val dotDrawable = AppCompatResources.getDrawable(activity, dot)
        kioskPill.setCompoundDrawablesRelativeWithIntrinsicBounds(dotDrawable, null, null, null)
        kioskBody.setText(ui.body)
        returnButton.visibility = if (ui.returnVisible) View.VISIBLE else View.GONE
        returnButton.isEnabled = ui.returnEnabled
        returnButton.setText(ui.returnLabel)
    }

    private fun color(id: Int): Int = ContextCompat.getColor(activity, id)
}
