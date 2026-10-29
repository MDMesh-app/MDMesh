package com.mdmesh.kiosk

import android.app.admin.DevicePolicyManager

/**
 * Kiosk UI toggles as delivered in a `kiosk.apply` command payload. Each is nullable:
 * `null` means "leave at the framework default for that feature".
 */
data class KioskToggles(
    val home: Boolean? = null,
    val recents: Boolean? = null,
    val notifications: Boolean? = null,
    val systemInfo: Boolean? = null,
    val keyguard: Boolean? = null,
    val lockButtons: Boolean? = null,
)

/**
 * Map [KioskToggles] to a `LOCK_TASK_FEATURE_*` bitmask for
 * [DevicePolicyManager.setLockTaskFeatures] (API 28+).
 *
 * Each enabling toggle adds its feature flag, except that Recents and Notifications need Home: Android refuses
 * [DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW] or [DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS] without
 * [DevicePolicyManager.LOCK_TASK_FEATURE_HOME], and the whole kiosk entry would fail. Without Home they are left
 * off (the stricter reading), never Home turned on. The power menu
 * ([DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS]) is ON by default — that is the
 * framework's own default in lock-task — and is removed only when [KioskToggles.lockButtons]
 * is explicitly `true` ("lock the hardware buttons"). That is the power menu only: lock task has no
 * volume feature flag; volume is [KioskController.setVolumeLocked] (`features.lockVolume`).
 */
fun lockTaskFeatures(t: KioskToggles): Int {
    var f = 0
    if (t.home == true) f = f or DevicePolicyManager.LOCK_TASK_FEATURE_HOME
    if (t.home == true && t.recents == true) f = f or DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW
    if (t.home == true && t.notifications == true) f = f or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
    if (t.systemInfo == true) f = f or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
    if (t.keyguard == true) f = f or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
    if (t.lockButtons != true) f = f or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
    return f
}
