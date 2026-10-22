package com.mdmesh.core.location

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.mdmesh.policy.wifi.DpmHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reconcile permissions at collection time, including agents upgraded without re-provisioning. */
@Singleton
class LocationPermissionAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val handle: DpmHandle,
) {
    private val repair = LocationPermissionRepair(
        isOwner = { handle.dpm.isDeviceOwnerApp(context.packageName) },
        isGranted = { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED },
        grant = {
            handle.dpm.setPermissionGrantState(
                handle.admin, context.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        },
    )

    fun ensure() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        val failed = runCatching { repair.ensure(permissions) }.getOrElse {
            Log.w(TAG, "Cannot reconcile location permissions", it)
            return
        }
        if (failed.isNotEmpty()) Log.w(TAG, "Location permissions unavailable: $failed")
    }

    private companion object {
        const val TAG = "LocationPermission"
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
