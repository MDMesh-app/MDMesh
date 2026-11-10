package com.mdmesh.agent

import androidx.annotation.StringRes
import com.mdmesh.kiosk.KioskExit
import com.mdmesh.proto.KioskApplyPayload

/**
 * What the agent asks before Return to kiosk locks the device. Staff left the kiosk with the exit PIN; returning
 * locks it again, so they confirm first, and are told plainly when this kiosk has no exit on the device at all.
 * [danger] marks that second case: its confirm button is styled as a destructive action.
 */
data class KioskReturnPrompt(
    @StringRes val title: Int,
    @StringRes val message: Int,
    @StringRes val confirm: Int,
    val danger: Boolean,
) {
    companion object {
        fun of(kiosk: KioskApplyPayload): KioskReturnPrompt =
            if (KioskExit.onDevice(kiosk)) {
                KioskReturnPrompt(
                    title = R.string.kiosk_return_title,
                    message = R.string.kiosk_return_message,
                    confirm = R.string.kiosk_return_action,
                    danger = false,
                )
            } else {
                KioskReturnPrompt(
                    title = R.string.kiosk_return_no_exit_title,
                    message = R.string.kiosk_return_no_exit_message,
                    confirm = R.string.kiosk_return_no_exit_confirm,
                    danger = true,
                )
            }
    }
}
