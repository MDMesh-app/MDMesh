package com.mdmesh.policy.apps

import com.mdmesh.policy.InMemoryLiftRestoreStore
import com.mdmesh.policy.LiftRestoreStore
import com.mdmesh.policy.wifi.DpmHandle

object UserAppUninstallPolicyFactory {
    fun create(handle: DpmHandle, store: LiftRestoreStore = InMemoryLiftRestoreStore()): UserAppUninstallPolicy? =
        UserAppUninstallRestrictionPolicy(handle, store).takeIf { it.isSupported() }
}
