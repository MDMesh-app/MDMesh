package com.mdmesh.kiosk

import com.mdmesh.proto.KioskApplyPayload

/**
 * Whether a kiosk can be left on the device itself. The kiosk launcher shows an exit only with an exit PIN
 * ([ExitPinGate.isAvailable]) and an exit control: the `visible` button or the `gesture` corner. `remote`, or a
 * kiosk without a PIN, has none: only the server ends it, so a device locked offline stays locked until it is back
 * online and an admin exits it from the console.
 */
object KioskExit {
    private val ON_DEVICE_MODES = setOf("visible", "gesture")

    fun onDevice(p: KioskApplyPayload): Boolean = !p.password.isNullOrBlank() && p.exitMode in ON_DEVICE_MODES
}
