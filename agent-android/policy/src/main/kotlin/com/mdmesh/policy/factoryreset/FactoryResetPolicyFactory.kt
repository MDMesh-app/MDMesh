package com.mdmesh.policy.factoryreset

import com.mdmesh.policy.wifi.DpmHandle

/**
 * Selects the [FactoryResetPolicy] strategy for the current device. Single candidate; factory
 * shape kept for uniformity. Returns `null` when no strategy is supported (e.g. not Device Owner),
 * in which case `factoryReset` is never advertised and therefore never commanded. Every strategy is
 * wrapped so a wipe in progress holds Block off ([FactoryResetWipeHold]), and, outside that, so kiosk mode holds it on
 * ([FactoryResetKioskHold]): the kiosk's hold is checked first, so an Allow it defers is never applied, and a Block
 * still meets the wipe's hold.
 */
object FactoryResetPolicyFactory {

    fun create(
        handle: DpmHandle,
        hold: FactoryResetWipeHold,
        kiosk: FactoryResetKioskHold = FactoryResetKioskHold.None,
    ): FactoryResetPolicy? =
        listOf(FactoryResetRestrictionPolicy(handle)).firstOrNull { it.isSupported() }
            ?.let { KioskHeldFactoryResetPolicy(WipeHeldFactoryResetPolicy(it, hold), kiosk) }
}
