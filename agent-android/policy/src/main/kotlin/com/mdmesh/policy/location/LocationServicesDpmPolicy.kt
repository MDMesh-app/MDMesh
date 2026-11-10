package com.mdmesh.policy.location

import android.os.Build
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.UserRestrictions
import com.mdmesh.policy.wifi.DpmHandle

/**
 * Device Owner location-services control introduced in Android 11/API 30.
 *
 * The restriction is applied after the switch itself: a Device Owner may change the global setting even while
 * `DISALLOW_CONFIG_LOCATION` is set, and ordering this way avoids leaving a user locked out after a failed switch.
 */
internal class LocationServicesDpmPolicy(
    private val handle: DpmHandle,
) : LocationServicesPolicy {

    override val capabilityKey: String = LocationServicesPolicy.CAPABILITY_KEY

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun setEnabled(enabled: Boolean): PolicyOutcome {
        // Keep the API guard in this call path as well as isSupported(): Android lint does not infer that callers
        // resolved this strategy through the factory, and the guard is the last safety net for a direct caller.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return PolicyOutcome.Unsupported
        return runCatching {
            handle.dpm.setLocationEnabled(handle.admin, enabled)
            // Device Owner may change the global switch despite this restriction. Apply it only after a successful
            // state change, so both On and Off keep the setting managed without locking the user out after a failure.
            handle.dpm.addUserRestriction(handle.admin, UserRestrictions.DISALLOW_CONFIG_LOCATION)
            PolicyOutcome.Applied
        }.getOrElse { PolicyOutcome.Failed(it.message ?: "location services setEnabled failed") }
    }

    override fun release(): PolicyOutcome = runCatching {
        handle.dpm.clearUserRestriction(handle.admin, UserRestrictions.DISALLOW_CONFIG_LOCATION)
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "location services release failed") }
}
