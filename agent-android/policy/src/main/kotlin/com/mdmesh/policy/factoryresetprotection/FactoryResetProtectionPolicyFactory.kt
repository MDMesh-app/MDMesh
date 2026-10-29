package com.mdmesh.policy.factoryresetprotection

import com.mdmesh.policy.wifi.DpmHandle

/**
 * Selects the [FactoryResetProtectionPolicy] strategy for the current device. Single candidate;
 * factory shape kept for uniformity. Returns `null` when no strategy is supported (API below 30 or
 * not Device Owner), in which case `factoryResetProtection` is never advertised.
 */
object FactoryResetProtectionPolicyFactory {

    fun create(handle: DpmHandle): FactoryResetProtectionPolicy? =
        listOf(FactoryResetProtectionDpmPolicy(handle)).firstOrNull { it.isSupported() }
}
