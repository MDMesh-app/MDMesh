package com.mdmesh.policy.factoryreset

import android.os.Build
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.UserRestrictions
import com.mdmesh.policy.wifi.DpmHandle

/**
 * Factory-reset strategy via the `DISALLOW_FACTORY_RESET` user restriction.
 *
 * The restriction exists from API 21, so the module floor (API 24) is always sufficient; the guard
 * keeps SDK discipline uniform. It is only *enforced* on a Device/Profile Owner, which is why
 * [isSupported] also requires Device Owner — otherwise the key would be advertised on a device
 * where the restriction is silently ignored. The restriction key comes from the pure
 * [UserRestrictions] helper, so this file holds no policy knowledge itself.
 *
 * Known limits, by design of the platform API:
 *  - It blocks the *user's* path through Settings only. A wipe from recovery mode
 *    (`adb reboot recovery`), `fastboot -w` and unlocking the bootloader are out of any DPC's reach.
 *  - It does not affect an administrator-initiated wipe (`wipeDevice`/`wipeData` are not subject
 *    to user restrictions), so the console's `device.wipe` keeps working on a blocked device.
 */
internal class FactoryResetRestrictionPolicy(
    private val handle: DpmHandle,
) : FactoryResetPolicy {

    override val capabilityKey: String = FactoryResetPolicy.CAPABILITY_KEY

    private val restrictions: Set<String> =
        UserRestrictions.forKey(FactoryResetPolicy.CAPABILITY_KEY).orEmpty()

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun setEnabled(enabled: Boolean): PolicyOutcome = runCatching {
        // Feature ON (enabled=true) => reset allowed => restriction cleared; OFF => blocked => added.
        restrictions.forEach { key ->
            if (enabled) {
                handle.dpm.clearUserRestriction(handle.admin, key)
            } else {
                handle.dpm.addUserRestriction(handle.admin, key)
            }
        }
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "factoryReset setEnabled failed") }
}
