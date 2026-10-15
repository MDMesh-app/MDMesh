package com.mdmesh.policy.apps

import android.os.Build
import com.mdmesh.policy.UserRestrictions
import com.mdmesh.policy.wifi.DpmHandle

/**
 * DPM access for an app restriction policy. The `DISALLOW_*` keys come from the pure [UserRestrictions] helper (as
 * for factoryReset and safeBoot), so these files hold no restriction names of their own. Device Owner only: the
 * restrictions are device-wide and the agent needs DO to lift them around its own installs.
 */
internal class AppRestrictionAccess(private val handle: DpmHandle, capabilityKey: String) {
    private val keys: Set<String> =
        requireNotNull(UserRestrictions.forKey(capabilityKey)?.takeIf { it.isNotEmpty() }) {
            "no user restriction for $capabilityKey"
        }

    fun supported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    fun isBlocked(): Boolean {
        val set = handle.dpm.getUserRestrictions(handle.admin)
        return keys.all { set.getBoolean(it) }
    }

    fun setBlocked(blocked: Boolean) {
        keys.forEach { key ->
            if (blocked) {
                handle.dpm.addUserRestriction(handle.admin, key)
            } else {
                handle.dpm.clearUserRestriction(handle.admin, key)
            }
        }
    }
}
