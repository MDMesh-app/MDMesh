package com.mdmesh.policy.wifi

import android.os.UserManager
import com.mdmesh.policy.PolicyOutcome

/**
 * Wi-Fi strategy for API 24–29 (Android 7–10).
 *
 * Predates [android.app.admin.DevicePolicyManager.setConfiguredNetworksLockdownState],
 * so config-locking degrades to the user-restriction path only. This is the
 * "old agent still works" branch the capability matrix is designed to protect.
 */
internal class LegacyWifiPolicy(
    private val handle: DpmHandle,
) : WifiPolicy {

    override val capabilityKey: String = WifiPolicy.CAPABILITY_KEY

    override fun isSupported(): Boolean =
        handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun setEnabled(enabled: Boolean): PolicyOutcome = runCatching {
        if (enabled) {
            handle.dpm.clearUserRestriction(handle.admin, UserManager.DISALLOW_CONFIG_WIFI)
        } else {
            handle.dpm.addUserRestriction(handle.admin, UserManager.DISALLOW_CONFIG_WIFI)
        }
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "legacy wifi setEnabled failed") }

    override fun setUserChangesBlocked(blocked: Boolean): PolicyOutcome = runCatching {
        // No lockdown API on this SDK; the user restriction is the strongest lever.
        if (blocked) {
            handle.dpm.addUserRestriction(handle.admin, UserManager.DISALLOW_CONFIG_WIFI)
        } else {
            handle.dpm.clearUserRestriction(handle.admin, UserManager.DISALLOW_CONFIG_WIFI)
        }
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "legacy wifi lockdown failed") }
}
