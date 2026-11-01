package com.mdmesh.policy.factoryresetprotection

import android.app.admin.FactoryResetProtectionPolicy as AndroidFactoryResetProtectionPolicy
import android.os.Build
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.wifi.DpmHandle

/*
 * The platform type shares this interface's simple name, so it is imported under an alias: the
 * strategy is the only place that touches it.
 */

/**
 * EFRP strategy over `DevicePolicyManager.setFactoryResetProtectionPolicy` (API 30+).
 *
 * Device Owner only: the platform ignores the policy for any other admin, so [isSupported] also
 * requires it — otherwise the key would be advertised on a device that silently drops it. Below
 * API 30 no strategy exists and `factoryResetProtection` is simply never advertised.
 *
 * [apply] builds a policy with the enabled flag and the account allow-list. When `enabled=false`
 * and no accounts are given the resulting policy explicitly turns FRP off; [release] clears the
 * whole policy back to the platform default (the device's own FRP behaviour).
 */
internal class FactoryResetProtectionDpmPolicy(
    private val handle: DpmHandle,
) : FactoryResetProtectionPolicy {

    override val capabilityKey: String = FactoryResetProtectionPolicy.CAPABILITY_KEY

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun apply(enabled: Boolean, accounts: List<String>): PolicyOutcome = runCatching {
        // "Disabled + allow-list" is contradictory; an explicit Off always wins and drops the accounts.
        val allowList = if (enabled) accounts else emptyList()
        val policy = AndroidFactoryResetProtectionPolicy.Builder()
            .setFactoryResetProtectionEnabled(enabled)
            .setFactoryResetProtectionAccounts(allowList)
            .build()
        handle.dpm.setFactoryResetProtectionPolicy(handle.admin, policy)
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "factoryResetProtection apply failed") }

    override fun release(): PolicyOutcome = runCatching {
        handle.dpm.setFactoryResetProtectionPolicy(handle.admin, null)
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "factoryResetProtection release failed") }
}
