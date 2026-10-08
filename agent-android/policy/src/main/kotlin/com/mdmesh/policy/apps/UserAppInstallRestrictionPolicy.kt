package com.mdmesh.policy.apps

import android.os.Build
import com.mdmesh.policy.TemporaryUserRestrictionPolicy
import com.mdmesh.policy.UserRestrictions
import com.mdmesh.policy.wifi.DpmHandle

internal class UserAppInstallRestrictionPolicy(handle: DpmHandle) : TemporaryUserRestrictionPolicy(
    capabilityKey = UserAppInstallPolicy.CAPABILITY_KEY,
    supported = {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            handle.dpm.isDeviceOwnerApp(handle.admin.packageName)
    },
    isBlocked = { handle.dpm.getUserRestrictions(handle.admin).getBoolean(UserRestrictions.DISALLOW_INSTALL_APPS) },
    setBlocked = { blocked ->
        if (blocked) handle.dpm.addUserRestriction(handle.admin, UserRestrictions.DISALLOW_INSTALL_APPS)
        else handle.dpm.clearUserRestriction(handle.admin, UserRestrictions.DISALLOW_INSTALL_APPS)
    },
), UserAppInstallPolicy
