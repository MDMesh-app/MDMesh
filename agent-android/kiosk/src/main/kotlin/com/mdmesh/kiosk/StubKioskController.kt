package com.mdmesh.kiosk

import android.content.ComponentName
import android.content.Context

/**
 * No-op [KioskController] used as a fallback when the agent is not the Device Owner
 * (so lock-task cannot be configured) or in tests/previews. Every operation reports
 * [KioskResult.Unsupported]; the device is never considered locked.
 *
 * Wire [LockTaskKioskController] instead whenever the agent is provisioned as
 * Device Owner.
 */
class StubKioskController : KioskController {

    override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult =
        KioskResult.Unsupported

    override fun exit(): KioskResult = KioskResult.Unsupported

    override fun isLocked(context: Context): Boolean = false

    override fun allowedPackages(): List<String> = emptyList()
}
