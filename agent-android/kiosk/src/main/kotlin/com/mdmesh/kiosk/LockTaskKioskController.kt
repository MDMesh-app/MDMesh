package com.mdmesh.kiosk

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.Build

/**
 * Real [KioskController] backed by the native lock-task APIs.
 *
 * Constructed directly with a [DevicePolicyManager] and the admin [ComponentName]
 * (no `DpmHandle` indirection). The agent's own package name is required to keep
 * the kiosk launcher on the allowlist and to scope the persistent-HOME claim; it is
 * resolved from the [Context] passed to [enter]/[exit].
 *
 * SDK floors:
 * - lock-task allowlisting / persistent HOME: API 21 (module minSdk is 24, so always met).
 * - [DevicePolicyManager.setLockTaskFeatures]: API 28 (P) — guarded.
 *
 * Every entry point checks [DevicePolicyManager.isDeviceOwnerApp] first and returns
 * [KioskResult.Unsupported] when the agent is not the Device Owner. Framework calls
 * are wrapped so a [SecurityException] (or any throwable) becomes
 * [KioskResult.Failed] rather than propagating.
 */
class LockTaskKioskController(
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
) : KioskController {

    override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult {
        val ownPackage = homeComponent.packageName
        if (!dpm.isDeviceOwnerApp(ownPackage)) return KioskResult.Unsupported
        return runGuarded {
            // Own package must stay allowlisted so the kiosk launcher can run.
            val allowlist = (allowedPackages + ownPackage).distinct().toTypedArray()
            dpm.setLockTaskPackages(admin, allowlist)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(admin, features)
            }

            // Claim HOME so pressing home returns to the kiosk launcher.
            val homeFilter = IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, homeFilter, homeComponent)
        }
    }

    /**
     * [exit] needs the agent's own package to release the persistent-HOME claim.
     * Resolve it from the admin component, which is always in the agent's package.
     */
    override fun exit(): KioskResult {
        val ownPackage = admin.packageName
        if (!dpm.isDeviceOwnerApp(ownPackage)) return KioskResult.Unsupported
        return runGuarded {
            dpm.setLockTaskPackages(admin, emptyArray())
            dpm.clearPackagePersistentPreferredActivities(admin, ownPackage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Reset features to the framework default (only SYSTEM_INFO / status bar).
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
        }
    }

    override fun isLocked(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
    }

    override fun allowedPackages(): List<String> {
        if (!dpm.isDeviceOwnerApp(admin.packageName)) return emptyList()
        // getLockTaskPackages() was added in API 26 (O); on 24/25 the method doesn't exist.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        return try {
            dpm.getLockTaskPackages(admin).toList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Run [block]; convert success to [KioskResult.Ok] and any throwable to [KioskResult.Failed]. */
    private inline fun runGuarded(block: () -> Unit): KioskResult =
        try {
            block()
            KioskResult.Ok
        } catch (t: Throwable) {
            KioskResult.Failed(t.message ?: t.javaClass.simpleName)
        }
}
