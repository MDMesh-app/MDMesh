package com.mdmesh.agent.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.mdmesh.agent.BuildConfig
import com.mdmesh.agent.R
import java.text.DateFormat
import java.util.Date

/**
 * The status screen's views (res/layout/activity_status.xml). [render] draws a whole [StatusUiState]; it is the
 * only code that changes these views after setup. [onReturn] is the Return to kiosk button.
 */
class StatusScreen(
    private val activity: Activity,
    onReturn: () -> Unit,
    onCopy: (StatusCopy.Target) -> Unit,
) {

    private val deviceId: TextView = activity.findViewById(R.id.status_device_id)
    private val server: TextView = activity.findViewById(R.id.status_server)
    private val management = Callout(activity.findViewById(R.id.status_management))
    private val enrollError = Callout(activity.findViewById(R.id.status_enroll_error), announce = true)
    private val kioskPill: TextView = activity.findViewById(R.id.status_kiosk_pill)
    private val kioskBody: TextView = activity.findViewById(R.id.status_kiosk_body)
    private val returnButton: Button = activity.findViewById(R.id.status_return)
    private val returnError = Callout(activity.findViewById(R.id.status_return_error), announce = true)
    private val kioskHeader: View = activity.findViewById(R.id.status_kiosk_header)
    private val copyDeviceId: ImageButton = activity.findViewById(R.id.status_copy_device_id)
    private val copyServer: ImageButton = activity.findViewById(R.id.status_copy_server)
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
        // TalkBack: the wordmark is the screen's title and "Kiosk" its card's; each fact is one stop, label and value.
        ViewCompat.setAccessibilityHeading(activity.findViewById(R.id.status_wordmark_text), true)
        ViewCompat.setAccessibilityHeading(activity.findViewById(R.id.status_kiosk_title), true)
        val version =
            activity.getString(R.string.status_agent_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        activity.findViewById<TextView>(R.id.status_version).text = version
        describe(R.id.status_row_version, R.string.status_label_agent_version, version)
        returnButton.setOnClickListener { onReturn() }
        copyDeviceId.setOnClickListener { onCopy(StatusCopy.Target.DEVICE_ID) }
        copyServer.setOnClickListener { onCopy(StatusCopy.Target.SERVER) }
    }

    fun render(state: StatusUiState) {
        val before = drawn
        drawn = state
        if (before?.deviceOwner != state.deviceOwner) renderManagement(state.deviceOwner)
        if (before?.deviceId != state.deviceId) renderDeviceId(state.deviceId)
        if (before?.serverUrl != state.serverUrl) {
            server.text = state.serverUrl
            describe(R.id.status_server_group, R.string.status_label_server, state.serverUrl)
        }
        if (before?.kiosk != state.kiosk) renderKiosk(state.kiosk)
        // Nothing to copy until the server has issued an ID; INVISIBLE keeps the row's layout steady.
        copyDeviceId.visibility = if (state.deviceId is StatusUiState.DeviceIdUi.Known) View.VISIBLE else View.INVISIBLE
        if (before?.copied != state.copied) {
            renderCopied(copyDeviceId, R.string.status_copy_device_id, state.copied == StatusCopy.Target.DEVICE_ID)
            renderCopied(copyServer, R.string.status_copy_server, state.copied == StatusCopy.Target.SERVER)
        }
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
        describe(R.id.status_device_id_group, R.string.status_label_device_id, deviceId.text)
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
        // One stop, "Kiosk, Off"; a polite live region, so a change (Active, Off) is read out.
        kioskHeader.contentDescription =
            "${activity.getString(R.string.status_label_kiosk)}, ${activity.getString(ui.pill)}"
    }

    /** The group [id] reads as "label, value" (its label and value TextViews are hidden from TalkBack). */
    private fun describe(id: Int, @StringRes label: Int, value: CharSequence) {
        activity.findViewById<View>(id).contentDescription = "${activity.getString(label)}, $value"
    }

    /**
     * "Copied" shows on the copy button itself, a check in the ok colour for a moment, so the row keeps its height;
     * TalkBack hears it too.
     */
    private fun renderCopied(button: ImageButton, @StringRes label: Int, copied: Boolean) {
        button.setImageResource(if (copied) R.drawable.ic_v2_check else R.drawable.ic_v2_copy)
        button.imageTintList = ColorStateList.valueOf(color(if (copied) R.color.mdm_ok_ink else R.color.mdm_secondary))
        button.contentDescription = activity.getString(if (copied) R.string.status_copied else label)
        if (copied) button.announceForAccessibility(activity.getString(R.string.status_copied))
    }

    private fun color(id: Int): Int = ContextCompat.getColor(activity, id)
}
