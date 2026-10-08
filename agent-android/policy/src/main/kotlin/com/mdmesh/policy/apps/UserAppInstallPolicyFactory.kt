package com.mdmesh.policy.apps

import com.mdmesh.policy.wifi.DpmHandle

object UserAppInstallPolicyFactory {
    fun create(handle: DpmHandle): UserAppInstallPolicy? =
        UserAppInstallRestrictionPolicy(handle).takeIf { it.isSupported() }
}
