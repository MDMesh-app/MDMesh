package com.mdmesh.policy.bluetooth

import com.mdmesh.policy.wifi.DpmHandle

/**
 * Selects the [BluetoothPolicy] strategy for the current device. The lone
 * candidate self-gates to API 26+; on older devices nothing is supported and
 * `bluetooth` is never advertised. Returns `null` when unsupported.
 */
object BluetoothPolicyFactory {

    fun create(handle: DpmHandle): BluetoothPolicy? =
        listOf(BluetoothRestrictionPolicy(handle)).firstOrNull { it.isSupported() }
}
