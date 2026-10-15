package com.mdmesh.policy.apps

import com.mdmesh.policy.LiftRestoreStore
import com.mdmesh.policy.TemporaryUserRestrictionPolicy
import com.mdmesh.policy.wifi.DpmHandle

/**
 * `userAppInstall` via `DISALLOW_INSTALL_APPS` (key from `UserRestrictions.forKey`).
 * [com.mdmesh.policy.ManagedAppPolicy.withAllowed] lifts it around MDMesh's own installs.
 */
internal class UserAppInstallRestrictionPolicy private constructor(
    access: AppRestrictionAccess,
    store: LiftRestoreStore,
) : TemporaryUserRestrictionPolicy(
    capabilityKey = UserAppInstallPolicy.CAPABILITY_KEY,
    supported = access::supported,
    isBlocked = access::isBlocked,
    setBlocked = access::setBlocked,
    store = store,
), UserAppInstallPolicy {
    constructor(handle: DpmHandle, store: LiftRestoreStore) :
        this(AppRestrictionAccess(handle, UserAppInstallPolicy.CAPABILITY_KEY), store)
}
