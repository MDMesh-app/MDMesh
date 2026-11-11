package com.mdmesh.policy.dns

import com.mdmesh.policy.wifi.DpmHandle

/**
 * Selects the [DnsPolicy] strategy for the current device.
 *
 * Returns `null` when no strategy is supported (e.g. API < 28 or not Device Owner),
 * in which case the `dns` capability is never advertised.
 */
object DnsPolicyFactory {

    fun create(handle: DpmHandle): DnsPolicy? =
        listOf(PrivateDnsPolicy(handle)).firstOrNull { it.isSupported() }
}
