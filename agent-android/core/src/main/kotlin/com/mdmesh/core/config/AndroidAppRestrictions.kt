package com.mdmesh.core.config

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.ProtocolJson

class SharedPrefsAppRestrictionStore(context: Context) : AppRestrictionStore {
    private val prefs = context.getSharedPreferences("mdm_app_restrictions", Context.MODE_PRIVATE)
    override fun load(): AppRestrictionState = prefs.getString("state", null)?.let {
        // Corrupt state must surface, never silently discard our restoration journal.
        ProtocolJson.json.decodeFromString(AppRestrictionState.serializer(), it)
    } ?: AppRestrictionState()

    override fun save(state: AppRestrictionState) {
        val encoded = ProtocolJson.json.encodeToString(AppRestrictionState.serializer(), state)
        check(prefs.edit().putString("state", encoded).commit()) {
            "Could not persist app restriction journal"
        }
    }
}

class AndroidAppRestrictionBackend(
    private val context: Context,
    private val handle: DpmHandle,
) : AppRestrictionBackend {
    private val pm = context.packageManager
    override fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        handle.dpm.isDeviceOwnerApp(context.packageName)

    @Suppress("DEPRECATION")
    override fun installedPackages(): Set<String> =
        pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        .filter { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }
        .map { it.packageName }.toSet()

    @Suppress("DEPRECATION")
    override fun isProtectedPackage(pkg: String): Boolean {
        if (pkg == context.packageName || pkg in SYSTEM_COMPONENTS) return true
        // OEM installers can have different names. Keep all registered APK handlers available.
        val installer = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://mdmesh/protected.apk"), "application/vnd.android.package-archive",
        )
        return pm.queryIntentActivities(installer, PackageManager.MATCH_DISABLED_COMPONENTS)
            .any { it.activityInfo.packageName == pkg }
    }

    override fun unknownSourcesBlocked(): Boolean =
        handle.dpm.getUserRestrictions(handle.admin).getBoolean(UNKNOWN_SOURCES)
    override fun setUnknownSourcesBlocked(blocked: Boolean) {
        if (blocked) handle.dpm.addUserRestriction(handle.admin, UNKNOWN_SOURCES)
        else handle.dpm.clearUserRestriction(handle.admin, UNKNOWN_SOURCES)
        check(unknownSourcesBlocked() == blocked) { "Unknown-sources restriction did not converge" }
    }

    override fun isHidden(pkg: String): Boolean = handle.dpm.isApplicationHidden(handle.admin, pkg)
    override fun setHidden(pkg: String, hidden: Boolean) {
        check(handle.dpm.setApplicationHidden(handle.admin, pkg, hidden) && isHidden(pkg) == hidden) {
            "Store visibility did not converge: $pkg"
        }
    }

    override fun isUninstallBlocked(pkg: String): Boolean = handle.dpm.isUninstallBlocked(handle.admin, pkg)
    override fun setUninstallBlocked(pkg: String, blocked: Boolean) {
        handle.dpm.setUninstallBlocked(handle.admin, pkg, blocked)
        check(isUninstallBlocked(pkg) == blocked) { "Uninstall protection did not converge: $pkg" }
    }

    private companion object {
        const val UNKNOWN_SOURCES = "no_install_unknown_sources"
        val SYSTEM_COMPONENTS = setOf(
            "android", "com.android.settings", "com.android.systemui", "com.android.packageinstaller",
            "com.google.android.packageinstaller", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.samsung.android.packageinstaller",
        )
    }
}
