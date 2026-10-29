package com.mdmesh.core.location

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.mdmesh.policy.wifi.DpmHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Before each collection, as Device Owner, grant any location permission the agent is missing: a QR-enrolled agent
 * can hold only the foreground grants, and agents upgraded without re-provisioning may lack background too. Cheap
 * when everything is granted. A missing grant is retried at most every 15 minutes and logged only when what's missing
 * changes, so a device that refuses it doesn't spam the log or DevicePolicyManager every check-in.
 */
@Singleton
class LocationPermissionAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val handle: DpmHandle,
) {
    private val repair = LocationPermissionRepair(
        isOwner = { handle.dpm.isDeviceOwnerApp(context.packageName) },
        isGranted = ::isGranted,
        grant = {
            handle.dpm.setPermissionGrantState(
                handle.admin, context.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        },
    )
    private val retries = Throttle(RETRY_EVERY_MS) { SystemClock.elapsedRealtime() }
    @Volatile private var lastMissing: List<String> = emptyList()

    fun ensure() {
        val permissions = LocationPermissions.forThisDevice()
        val missing = when {
            permissions.all(::isGranted) -> emptyList()
            !retries.tryUse() -> return
            else -> runCatching { repair.ensure(permissions) }.getOrElse {
                Log.w(TAG, "Cannot reconcile location permissions", it)
                permissions.filterNot(::isGranted)
            }
        }
        if (missing != lastMissing && missing.isNotEmpty()) Log.w(TAG, "Location permissions unavailable: $missing")
        lastMissing = missing
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "LocationPermission"
        const val RETRY_EVERY_MS = 15 * 60_000L
    }
}

/** Foreground permissions precede background; verify actual grants instead of assuming DPM succeeded. */
internal class LocationPermissionRepair(
    private val isOwner: () -> Boolean,
    private val isGranted: (String) -> Boolean,
    private val grant: (String) -> Boolean,
) {
    fun ensure(permissions: List<String>): List<String> {
        val owner = isOwner()
        return permissions.filter { permission ->
            if (!isGranted(permission) && owner) runCatching { grant(permission) }
            !isGranted(permission)
        }
    }
}
