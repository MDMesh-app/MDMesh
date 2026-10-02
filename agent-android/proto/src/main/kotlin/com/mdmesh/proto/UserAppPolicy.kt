package com.mdmesh.proto

/** Desired-state policies; true means allowed. They require config.apply, not policy.apply. */
object UserAppPolicy {
    const val INSTALL = "userAppInstall"
    const val UNINSTALL = "userAppUninstall"
    val KEYS = setOf(INSTALL, UNINSTALL)
    val DEFAULT_STORES = setOf("com.android.vending", "com.sec.android.app.samsungapps")
}
