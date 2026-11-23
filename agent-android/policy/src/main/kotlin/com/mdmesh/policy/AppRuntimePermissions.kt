package com.mdmesh.policy

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Build
import com.mdmesh.policy.wifi.DpmHandle

/** Per-app runtime permission control used only by the additive `config.apply.appPermissions` section. */
interface AppRuntimePermissions {
    fun set(packageName: String, permission: String, state: String): AppPermissionOutcome

    /** Remove MDMesh's explicit override; the device-wide runtime-permission policy then governs the result. */
    fun release(packageName: String, permission: String): AppPermissionOutcome = set(packageName, permission, DEFAULT)

    companion object {
        const val GRANT = "grant"
        const val DENY = "deny"
        const val DEFAULT = "default"
    }
}

sealed interface AppPermissionOutcome {
    data object Applied : AppPermissionOutcome
    data object Unsupported : AppPermissionOutcome
    data object NotRequested : AppPermissionOutcome
    /** The configured app has not been installed yet; config.apply must be retried after app.install. */
    data object NotInstalled : AppPermissionOutcome
    data class Failed(val reason: String) : AppPermissionOutcome
}

/** Safe fallback for JVM wiring/tests before an Android Device Owner implementation is supplied. */
object UnsupportedAppRuntimePermissions : AppRuntimePermissions {
    override fun set(packageName: String, permission: String, state: String): AppPermissionOutcome =
        AppPermissionOutcome.Unsupported
}

/** Android Device Owner implementation. It never manages a permission that the installed package did not request. */
class DeviceOwnerAppRuntimePermissions(
    private val context: Context,
    private val handle: DpmHandle,
) : AppRuntimePermissions {
    @Suppress("ReturnCount") // Each distinct platform result is part of the agent/server contract.
    override fun set(packageName: String, permission: String, state: String): AppPermissionOutcome {
        if (!handle.dpm.isDeviceOwnerApp(handle.admin.packageName) || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return AppPermissionOutcome.Unsupported
        }
        val requested = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty().contains(permission)
        } catch (_: NameNotFoundException) {
            return AppPermissionOutcome.NotInstalled
        } catch (e: SecurityException) {
            return AppPermissionOutcome.Failed(e.message ?: "package lookup failed")
        }
        if (!requested) return AppPermissionOutcome.NotRequested
        val grantState = when (state) {
            AppRuntimePermissions.GRANT -> DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            AppRuntimePermissions.DENY -> DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
            AppRuntimePermissions.DEFAULT -> DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
            else -> return AppPermissionOutcome.Failed("invalid state")
        }
        return runCatching {
            if (handle.dpm.setPermissionGrantState(handle.admin, packageName, permission, grantState)) {
                AppPermissionOutcome.Applied
            } else AppPermissionOutcome.Failed("Android rejected the permission change")
        }.getOrElse { AppPermissionOutcome.Failed(it.message ?: "permission policy failed") }
    }
}
