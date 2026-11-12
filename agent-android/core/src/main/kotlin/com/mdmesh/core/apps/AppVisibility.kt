package com.mdmesh.core.apps

import com.mdmesh.policy.wifi.DpmHandle

/** Platform seam for reversible Device Owner app hiding; the package and its data are retained. */
interface AppVisibility {
    fun isDeviceOwner(): Boolean
    fun isHidden(pkg: String): Boolean
    fun setHidden(pkg: String, hidden: Boolean): Boolean
}

class AndroidAppVisibility(private val handle: DpmHandle) : AppVisibility {
    override fun isDeviceOwner(): Boolean = handle.dpm.isDeviceOwnerApp(handle.admin.packageName)
    override fun isHidden(pkg: String): Boolean = handle.dpm.isApplicationHidden(handle.admin, pkg)
    override fun setHidden(pkg: String, hidden: Boolean): Boolean =
        handle.dpm.setApplicationHidden(handle.admin, pkg, hidden)
}
