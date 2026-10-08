package com.mdmesh.policy.apps

import com.mdmesh.policy.ManagedAppPolicy

/** true = allowed, false = blocked, matching config.apply and policy.apply. */
interface UserAppUninstallPolicy : ManagedAppPolicy {
    companion object { const val CAPABILITY_KEY = "userAppUninstall" }
}
