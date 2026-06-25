package com.mdmesh.policy.bluetooth

import android.os.Build
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.UserRestrictions
import com.mdmesh.policy.wifi.DpmHandle

/**
 * Bluetooth strategy via the `DISALLOW_BLUETOOTH` user restriction.
 *
 * This restriction is only enforced from API 26 (O); on lower SDKs the device
 * cannot honour it, so [isSupported] returns false and the `bluetooth` capability
 * is never advertised — exactly the "degrade, don't crash" contract. The
 * restriction key set comes from the pure [UserRestrictions] helper.
 */
internal class BluetoothRestrictionPolicy(
    private val handle: DpmHandle,
) : BluetoothPolicy {

    override val capabilityKey: String = BluetoothPolicy.CAPABILITY_KEY

    private val restrictions: Set<String> =
        UserRestrictions.forKey(BluetoothPolicy.CAPABILITY_KEY).orEmpty()

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun setEnabled(enabled: Boolean): PolicyOutcome = runCatching {
        // Feature ON (enabled=true) => restriction cleared; OFF => restriction added.
        restrictions.forEach { key ->
            if (enabled) {
                handle.dpm.clearUserRestriction(handle.admin, key)
            } else {
                handle.dpm.addUserRestriction(handle.admin, key)
            }
        }
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "bluetooth setEnabled failed") }
}
