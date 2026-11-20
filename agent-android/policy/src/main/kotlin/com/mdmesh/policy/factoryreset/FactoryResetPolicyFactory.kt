package com.mdmesh.policy.factoryreset

import com.mdmesh.policy.wifi.DpmHandle

/**
 * Selects the [FactoryResetPolicy] strategy for the current device. Single candidate; factory
 * shape kept for uniformity. Returns `null` when no strategy is supported (e.g. not Device Owner),
 * in which case `factoryReset` is never advertised and therefore never commanded. Every strategy is
 * wrapped so a wipe in progress holds Block off ([FactoryResetWipeHold]).
 */
object FactoryResetPolicyFactory {

    fun create(handle: DpmHandle, hold: FactoryResetWipeHold): FactoryResetPolicy? =
        listOf(FactoryResetRestrictionPolicy(handle)).firstOrNull { it.isSupported() }
            ?.let { WipeHeldFactoryResetPolicy(it, hold) }
}
