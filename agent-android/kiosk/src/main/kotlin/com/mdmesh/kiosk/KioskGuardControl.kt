package com.mdmesh.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.UserManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The device calls the kiosk guard makes (`KioskGuard` in `:core`): the runtime-permission policy and per-app grant
 * states, uninstall blocks and user restrictions. [DpmKioskGuardControl] on a device; a fake in tests.
 *
 * Every call is checked against the Device Owner first and never throws. A setter returns true only when a read-back
 * shows the device in the asked state; a getter returns null when the answer can't be read.
 */
@Suppress("TooManyFunctions") // one function per device call the guard makes: the seam its tests fake
interface KioskGuardControl {
    fun isDeviceOwner(): Boolean

    /**
     * The restriction that stops installs from unknown sources: device-wide on Android 10+
     * (`DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY`), this user's below. The Device Owner's own installs aren't
     * affected.
     */
    val unknownSourcesRestriction: String get() = UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES

    /** `DevicePolicyManager.PERMISSION_POLICY_*`, or null when it can't be read. */
    fun permissionPolicy(): Int?
    fun setPermissionPolicy(policy: Int): Boolean

    /** `PERMISSION_GRANT_STATE_*`: anything but DEFAULT means the permission is fixed by policy. Null: unknown. */
    fun grantState(pkg: String, permission: String): Int?
    fun setGrantState(pkg: String, permission: String, state: Int): Boolean
    fun isGranted(pkg: String, permission: String): Boolean

    /**
     * False on Android 12+ when whoever set up the device declined to let the admin grant sensor permissions
     * (location, camera, microphone, body sensors). The device-wide auto-grant then can't answer those, and Android
     * asks the person instead. True below Android 12, where there is no such choice.
     */
    fun canAdminGrantSensors(): Boolean

    /** The runtime (dangerous) permissions [pkg] requests; null when it isn't installed or can't be read. */
    fun requestedRuntimePermissions(pkg: String): List<String>?

    /** Every installed package's name; null when they can't be listed (unknown, never "none"). */
    fun installedPackages(): List<String>?

    /**
     * Every installed package's runtime permissions, in one listing (a snapshot of the whole device runs on the way to
     * locking a kiosk, so it is kept to one call per app's grant state); null when they can't be listed.
     */
    fun runtimePermissionsByApp(): Map<String, List<String>>?

    fun setUninstallBlocked(pkg: String, blocked: Boolean): Boolean

    /** Whether this admin blocks [pkg]'s uninstall; null when it can't be read. */
    fun isUninstallBlocked(pkg: String): Boolean?

    /** Whether this admin sets the user restriction [key]; null when it can't be read. */
    fun holdsRestriction(key: String): Boolean?
    fun setRestriction(key: String, on: Boolean): Boolean

    /** Not the Device Owner: nothing to guard, nothing done. For tests and wiring before a device is set up. */
    @Suppress("TooManyFunctions")
    object None : KioskGuardControl {
        override fun isDeviceOwner() = false
        override fun permissionPolicy(): Int? = null
        override fun setPermissionPolicy(policy: Int) = false
        override fun grantState(pkg: String, permission: String): Int? = null
        override fun setGrantState(pkg: String, permission: String, state: Int) = false
        override fun isGranted(pkg: String, permission: String) = false
        override fun canAdminGrantSensors() = true
        override fun requestedRuntimePermissions(pkg: String): List<String>? = null
        override fun installedPackages(): List<String>? = null
        override fun runtimePermissionsByApp(): Map<String, List<String>>? = null
        override fun setUninstallBlocked(pkg: String, blocked: Boolean) = false
        override fun isUninstallBlocked(pkg: String): Boolean? = null
        override fun holdsRestriction(key: String): Boolean? = null
        override fun setRestriction(key: String, on: Boolean) = false
    }
}

/** [KioskGuardControl] on the real [DevicePolicyManager] and [PackageManager]. */
@Suppress("TooManyFunctions")
class DpmKioskGuardControl(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
) : KioskGuardControl {

    private val pm: PackageManager get() = context.packageManager

    override val unknownSourcesRestriction: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
        } else {
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
        }

    /** Whether each permission seen is a runtime one: a snapshot asks for every app's, mostly the same few. */
    private val runtime = ConcurrentHashMap<String, Boolean>()

    override fun isDeviceOwner(): Boolean = runCatching { dpm.isDeviceOwnerApp(admin.packageName) }.getOrDefault(false)

    override fun permissionPolicy(): Int? = owner { dpm.getPermissionPolicy(admin) }

    override fun setPermissionPolicy(policy: Int): Boolean =
        owner { dpm.setPermissionPolicy(admin, policy) } != null && permissionPolicy() == policy

    override fun grantState(pkg: String, permission: String): Int? =
        owner { dpm.getPermissionGrantState(admin, pkg, permission) }

    override fun setGrantState(pkg: String, permission: String, state: Int): Boolean =
        owner { dpm.setPermissionGrantState(admin, pkg, permission, state) } == true &&
            grantState(pkg, permission) == state

    override fun isGranted(pkg: String, permission: String): Boolean =
        runCatching { pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    override fun canAdminGrantSensors(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { dpm.canAdminGrantSensorsPermissions() }.getOrDefault(false)

    override fun requestedRuntimePermissions(pkg: String): List<String>? = runCatching {
        @Suppress("DEPRECATION") // the flags overload taking PackageInfoFlags is API 33+
        runtimeOf(pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS))
    }.getOrNull()

    override fun installedPackages(): List<String>? = runCatching {
        @Suppress("DEPRECATION") // as above
        pm.getInstalledPackages(0).map { it.packageName }
    }.getOrNull()

    override fun runtimePermissionsByApp(): Map<String, List<String>>? = runCatching {
        @Suppress("DEPRECATION") // as above
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).associate { it.packageName to runtimeOf(it) }
    }.getOrNull()

    // Each setter writes only when the device differs: a re-arm runs on every configuration apply.
    override fun isUninstallBlocked(pkg: String): Boolean? = owner { dpm.isUninstallBlocked(admin, pkg) }

    override fun setUninstallBlocked(pkg: String, blocked: Boolean): Boolean {
        fun isSo() = isUninstallBlocked(pkg) == blocked
        return isSo() || (owner { dpm.setUninstallBlocked(admin, pkg, blocked) } != null && isSo())
    }

    override fun holdsRestriction(key: String): Boolean? = owner { dpm.getUserRestrictions(admin).getBoolean(key) }

    override fun setRestriction(key: String, on: Boolean): Boolean {
        fun isSo() = holdsRestriction(key) == on
        fun set() = owner { if (on) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key) }
        return isSo() || (set() != null && isSo())
    }

    private fun runtimeOf(info: PackageInfo): List<String> =
        info.requestedPermissions.orEmpty().filter { perm ->
            runtime.getOrPut(perm) { runCatching { isRuntime(pm.getPermissionInfo(perm, 0)) }.getOrDefault(false) }
        }

    private fun isRuntime(info: PermissionInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.protection == PermissionInfo.PROTECTION_DANGEROUS
        } else {
            @Suppress("DEPRECATION")
            info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS
        }

    /** [block] as the Device Owner; null when not the owner or when it threw. */
    private inline fun <T> owner(block: () -> T): T? =
        if (!isDeviceOwner()) null else runCatching(block).getOrNull()
}
