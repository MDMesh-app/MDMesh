package com.mdmesh.agent

import com.mdmesh.kiosk.KioskExit
import com.mdmesh.proto.KioskApplyPayload

/**
 * What the agent asks before Return to kiosk locks the device. Staff left the kiosk with the exit PIN; returning
 * locks it again, so they confirm first, and are told plainly when this kiosk has no exit on the device at all.
 */
data class KioskReturnPrompt(val title: String, val message: String, val confirm: String) {
    companion object {
        fun of(kiosk: KioskApplyPayload): KioskReturnPrompt =
            if (KioskExit.onDevice(kiosk)) {
                KioskReturnPrompt(
                    title = "Return to kiosk?",
                    message = "The device locks to the kiosk again. To leave it, you'll need the exit PIN.",
                    confirm = "Return to kiosk",
                )
            } else {
                KioskReturnPrompt(
                    title = "Lock with no way out here?",
                    message = "This kiosk has no exit on the device. Once it locks, only an admin in the MDMesh " +
                        "console can unlock it, and only while the device is online.",
                    confirm = "Lock anyway",
                )
            }
    }
}
