package com.mdmesh.core.apps

import com.mdmesh.core.store.KioskStateStore

/** Device-side protection is shared by removal, hiding and the inventory's action hints. */
class AppActionGuard(
    private val agentPackage: String,
    private val kiosk: KioskStateStore,
    private val systemApp: (String) -> Boolean,
    private val administrators: () -> Set<String>,
) {
    suspend fun protectedPackages(): Set<String> {
        val active = kiosk.load()
        return administrators() + ESSENTIAL + agentPackage +
            active?.allowedPackages.orEmpty() + listOfNotNull(active?.pinPackage)
    }

    suspend fun reason(pkg: String, uninstall: Boolean): String? {
        val packages = protectedPackages()
        return reason(pkg, packages) ?: if (uninstall && systemApp(pkg)) "System apps cannot be uninstalled" else null
    }

    fun reason(pkg: String, packages: Set<String>): String? = when {
        !PACKAGE.matches(pkg) -> "Invalid package name"
        pkg in packages -> "The agent, active administrators, essential components and kiosk apps are protected"
        else -> null
    }

    private companion object {
        val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
        val ESSENTIAL = setOf(
            "android", "com.android.systemui", "com.android.settings",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.mdmesh.agent", "com.mdmesh.agent.debug",
        )
    }
}
