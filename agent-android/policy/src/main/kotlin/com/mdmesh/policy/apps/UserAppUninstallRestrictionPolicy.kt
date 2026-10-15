package com.mdmesh.policy.apps

import com.mdmesh.policy.LiftRestoreStore
import com.mdmesh.policy.TemporaryUserRestrictionPolicy
import com.mdmesh.policy.wifi.DpmHandle

/**
 * `userAppUninstall` via `DISALLOW_UNINSTALL_APPS` (key from `UserRestrictions.forKey`).
 * [com.mdmesh.policy.ManagedAppPolicy.withAllowed] lifts it around MDMesh's own uninstalls.
 */
internal class UserAppUninstallRestrictionPolicy private constructor(
    access: AppRestrictionAccess,
    store: LiftRestoreStore,
) : TemporaryUserRestrictionPolicy(
    capabilityKey = UserAppUninstallPolicy.CAPABILITY_KEY,
    supported = access::supported,
    isBlocked = access::isBlocked,
    setBlocked = access::setBlocked,
    store = store,
), UserAppUninstallPolicy {
    constructor(handle: DpmHandle, store: LiftRestoreStore) :
        this(AppRestrictionAccess(handle, UserAppUninstallPolicy.CAPABILITY_KEY), store)
}
