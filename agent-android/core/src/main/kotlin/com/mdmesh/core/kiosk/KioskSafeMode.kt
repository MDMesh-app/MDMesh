package com.mdmesh.core.kiosk

import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.kiosk.safeModeBlocked

/**
 * Sets Safe Mode for [KioskApplier]: what [safeModeBlocked] says for the kiosk state it is given and the
 * configuration's [setting]. Only a block that fails is reported (it leaves Safe Mode open, so the document is
 * re-applied). A lift that fails leaves Safe Mode blocked, which is safe, and reporting it would fail every document
 * on a device that refuses the call.
 */
internal class KioskSafeMode(
    private val kiosk: KioskController,
    private val setting: SafeModeSetting,
) {
    fun set(inKiosk: Boolean): KioskResult {
        val blocked = safeModeBlocked(inKiosk, setting.current())
        val r = kiosk.setSafeModeBlocked(blocked)
        return if (blocked) r else KioskResult.Ok
    }
}
