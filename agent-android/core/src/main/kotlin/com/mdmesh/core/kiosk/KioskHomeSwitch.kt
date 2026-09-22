package com.mdmesh.core.kiosk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** The Android side effects of entering/leaving kiosk, behind an interface so the orchestration is unit-testable. */
interface KioskHomeSwitch {
    /** Enable/disable the agent's HOME activity-alias (the persistent-preferred HOME while in kiosk). */
    fun setClaimEnabled(enabled: Boolean)
    /** Bring the kiosk launcher forward (best-effort; the HOME claim covers the next HOME press). */
    fun showLauncher()
    /** Send the device to the OEM launcher after kiosk exit. */
    fun showOemHome()
}

class AndroidKioskHomeSwitch(private val context: Context, private val homeComponent: ComponentName) : KioskHomeSwitch {
    override fun setClaimEnabled(enabled: Boolean) {
        runCatching {
            val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            context.packageManager.setComponentEnabledSetting(homeComponent, state, PackageManager.DONT_KILL_APP)
        }
    }
    override fun showLauncher() {
        runCatching { context.startActivity(Intent().setComponent(homeComponent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    override fun showOemHome() {
        runCatching { context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
