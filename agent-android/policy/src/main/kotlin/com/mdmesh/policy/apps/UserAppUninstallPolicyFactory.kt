package com.mdmesh.policy.apps

import com.mdmesh.policy.wifi.DpmHandle

object UserAppUninstallPolicyFactory {
    fun create(handle: DpmHandle): UserAppUninstallPolicy? =
        UserAppUninstallRestrictionPolicy(handle).takeIf { it.isSupported() }
}
