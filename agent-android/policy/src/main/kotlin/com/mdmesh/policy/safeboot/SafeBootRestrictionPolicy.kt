package com.mdmesh.policy.safeboot

import android.os.Build
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.UserRestrictions
import com.mdmesh.policy.wifi.DpmHandle

/**
 * Safe-Mode strategy via the `DISALLOW_SAFE_BOOT` user restriction.
 *
 * The restriction exists from API 23, so the module floor (API 24) is always sufficient; the guard
 * keeps SDK discipline uniform. It is a device-wide restriction only a Device Owner can set, which
 * is why [isSupported] also requires Device Owner — otherwise the key would be advertised on a
 * device where the restriction is not honoured. The restriction key comes from the pure
 * [UserRestrictions] helper, so this file holds no policy knowledge itself.
 *
 * Known limits, by design of the platform API:
 *  - It blocks the *user's* path into Safe Mode. How an OEM's own boot-time key combo honours it
 *    is up to the OEM build; verify on the target hardware.
 *  - Recovery mode and the bootloader are out of any DPC's reach.
 */
internal class SafeBootRestrictionPolicy(
    private val handle: DpmHandle,
) : SafeBootPolicy {

    override val capabilityKey: String = SafeBootPolicy.CAPABILITY_KEY

    private val restrictions: Set<String> =
        UserRestrictions.forKey(SafeBootPolicy.CAPABILITY_KEY).orEmpty()

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun setEnabled(enabled: Boolean): PolicyOutcome = runCatching {
        // Feature ON (enabled=true) => Safe Mode allowed => restriction cleared; OFF => blocked => added.
        restrictions.forEach { key ->
            if (enabled) {
                handle.dpm.clearUserRestriction(handle.admin, key)
            } else {
                handle.dpm.addUserRestriction(handle.admin, key)
            }
        }
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "safeBoot setEnabled failed") }
}
