package com.mdmesh.policy.safeboot

import com.mdmesh.policy.wifi.DpmHandle

/**
 * Selects the [SafeBootPolicy] strategy for the current device. Single candidate; factory shape
 * kept for uniformity. Returns `null` when no strategy is supported (e.g. not Device Owner), in
 * which case `safeBoot` is never advertised and therefore never commanded.
 */
object SafeBootPolicyFactory {

    fun create(handle: DpmHandle): SafeBootPolicy? =
        listOf(SafeBootRestrictionPolicy(handle)).firstOrNull { it.isSupported() }
}
