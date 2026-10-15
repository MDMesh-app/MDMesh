package com.mdmesh.policy.wifi

import android.os.Build
import android.os.UserManager
import com.mdmesh.policy.PolicyOutcome

/**
 * Wi-Fi strategy for API 30+ (Android 11+).
 *
 * Uses the modern lockdown API ([DevicePolicyManager.setConfiguredNetworksLockdownState])
 * for config locking on top of the user restriction. Kept deliberately small; all
 * raw DPM calls are confined here.
 */
internal class ModernWifiPolicy(
    private val handle: DpmHandle,
) : WifiPolicy {

    override val capabilityKey: String = WifiPolicy.CAPABILITY_KEY

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun setEnabled(enabled: Boolean): PolicyOutcome = runCatching {
        // `wifi` locks or unlocks the Wi-Fi settings only (DISALLOW_CONFIG_WIFI); the radio
        // itself is the separate `wifiRadio` policy (WifiRadioPolicy), so a stored wifi=false
        // never turns a device's Wi-Fi off.
        if (enabled) {
            handle.dpm.clearUserRestriction(handle.admin, UserManager.DISALLOW_CONFIG_WIFI)
        } else {
            handle.dpm.addUserRestriction(handle.admin, UserManager.DISALLOW_CONFIG_WIFI)
        }
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "modern wifi setEnabled failed") }

    override fun setUserChangesBlocked(blocked: Boolean): PolicyOutcome = runCatching {
        // setConfiguredNetworksLockdownState is API 30+. This strategy is only selected when
        // isSupported() (SDK_INT >= R), but guard explicitly so lint sees it too.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            handle.dpm.setConfiguredNetworksLockdownState(handle.admin, blocked)
        }
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "modern wifi lockdown failed") }
}
