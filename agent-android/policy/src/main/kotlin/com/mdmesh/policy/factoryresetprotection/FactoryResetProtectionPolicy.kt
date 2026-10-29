package com.mdmesh.policy.factoryresetprotection

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.PolicyStrategy
import com.mdmesh.policy.ReleasablePolicy

/**
 * Capability-abstracted control over **Enhanced Factory Reset Protection** (EFRP).
 *
 * Unlike `factoryReset` ([com.mdmesh.policy.factoryreset.FactoryResetPolicy]) this does *not* prevent a
 * wipe: it decides whether a wiped device demands an authorized Google account at setup, and which
 * accounts may unlock it
 * ([android.app.admin.DevicePolicyManager.setFactoryResetProtectionPolicy], API 30).
 *
 * It is deliberately **not** a [com.mdmesh.policy.TogglePolicy]: the desired state is not a bare
 * boolean but a pair — [apply] takes the enabled flag *and* the account allow-list — so it is not
 * registered in `CapabilityRegistry.togglePolicies()` and is never routed through the boolean
 * `policy.apply` command. `config.apply` carries it as its own section
 * (`factoryResetProtection`), and [release] clears the platform policy when a configuration stops
 * managing it (back to the device default).
 *
 * Selected by [FactoryResetProtectionPolicyFactory].
 */
interface FactoryResetProtectionPolicy : PolicyStrategy, ReleasablePolicy {

    /**
     * Apply the EFRP policy: `enabled` decides whether the wiped device demands an authorized
     * account, and `accounts` is the Google-account allow-list permitted to bypass FRP at setup
     * (empty = the platform default: the device's last account). Never throws.
     */
    fun apply(enabled: Boolean, accounts: List<String>): PolicyOutcome

    companion object {
        const val CAPABILITY_KEY = "factoryResetProtection"
    }
}
