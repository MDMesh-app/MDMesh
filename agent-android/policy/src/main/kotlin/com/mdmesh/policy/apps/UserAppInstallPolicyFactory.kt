package com.mdmesh.policy.apps

import com.mdmesh.policy.InMemoryLiftRestoreStore
import com.mdmesh.policy.LiftRestoreStore
import com.mdmesh.policy.wifi.DpmHandle

object UserAppInstallPolicyFactory {
    fun create(handle: DpmHandle, store: LiftRestoreStore = InMemoryLiftRestoreStore()): UserAppInstallPolicy? =
        UserAppInstallRestrictionPolicy(handle, store).takeIf { it.isSupported() }
}
