package com.mdmesh.policy.wifi

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy

/**
 * Capability-abstracted Wi-Fi control — the one fully worked example of the
 * capability-abstraction layer in this scaffold.
 *
 * Two concrete strategies implement this, branching on `Build.VERSION.SDK_INT`:
 *  - [com.mdmesh.policy.wifi.ModernWifiPolicy] (API 30+) uses
 *    `DevicePolicyManager.setConfiguredNetworksLockdownState` /
 *    `addUserRestriction(DISALLOW_CONFIG_WIFI)` style modern APIs.
 *  - [com.mdmesh.policy.wifi.LegacyWifiPolicy] (API 24–29) falls back to the
 *    older user-restriction path.
 *
 * [WifiPolicyFactory] selects the right one once, at construction. Feature code
 * only ever sees this interface.
 */
interface WifiPolicy : TogglePolicy {

    /** Enable or disable Wi-Fi as Device Owner. (from [TogglePolicy]) */
    override fun setEnabled(enabled: Boolean): PolicyOutcome

    /** Lock the user out of changing Wi-Fi configuration. */
    fun setUserChangesBlocked(blocked: Boolean): PolicyOutcome

    companion object {
        const val CAPABILITY_KEY = "wifi"
    }
}
