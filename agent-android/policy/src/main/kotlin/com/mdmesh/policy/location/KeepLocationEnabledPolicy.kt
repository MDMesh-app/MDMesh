package com.mdmesh.policy.location

import android.os.Build
import android.os.UserManager
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.ReleasablePolicy
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.policy.wifi.DpmHandle

/** On keeps system location enabled; Off/release only unlocks it, never disables location. */
class KeepLocationEnabledPolicy(
    private val turnOn: () -> Unit,
    private val lock: (Boolean) -> Unit,
    private val supported: () -> Boolean = { true },
) : TogglePolicy, ReleasablePolicy {
    override val capabilityKey: String = CAPABILITY_KEY
    override fun isSupported(): Boolean = supported()

    override fun setEnabled(enabled: Boolean): PolicyOutcome = runCatching {
        if (enabled) turnOn()
        lock(enabled)
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "location policy failed") }

    override fun release(): PolicyOutcome = setEnabled(false)

    companion object {
        const val CAPABILITY_KEY = "keepLocationEnabled"
    }
}

/** Only Device Owners on API 30+ can enable location through the public DPM API. */
object KeepLocationEnabledPolicyFactory {
    fun create(handle: DpmHandle): KeepLocationEnabledPolicy? {
        val dpm = handle.dpm
        val admin = handle.admin
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !dpm.isDeviceOwnerApp(admin.packageName)) return null
        return KeepLocationEnabledPolicy(
            supported = { dpm.isDeviceOwnerApp(admin.packageName) },
            turnOn = { dpm.setLocationEnabled(admin, true) },
            lock = { locked ->
                if (locked) dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_LOCATION)
                else dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_LOCATION)
                check(dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONFIG_LOCATION) == locked) {
                    "location restriction was not applied"
                }
            },
        )
    }
}
