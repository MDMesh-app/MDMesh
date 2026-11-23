package com.mdmesh.core.action

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

/**
 * As Device Owner, grant POST_NOTIFICATIONS (Android 13+) when the agent is missing it: a QR-enrolled agent never
 * opens MainActivity, and without it alerts and the "MDMesh active" notification are dropped without an error.
 * Called where the agent posts notifications (alerts, CheckInService start), so devices enrolled before provisioning
 * granted it are repaired too. Cheap when granted; a missing grant is logged only when that changes.
 */
@Singleton
class NotificationPermissionAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val handle: DpmHandle,
) {
    private val repair = NotificationPermissionRepair(
        sdkInt = Build.VERSION.SDK_INT,
        isOwner = { handle.dpm.isDeviceOwnerApp(context.packageName) },
        isGranted = {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        },
        grant = {
            handle.dpm.setPermissionGrantState(
                handle.admin, context.packageName, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        },
    )
    @Volatile private var lastGranted = true

    /** True when the agent holds POST_NOTIFICATIONS (or doesn't need it) afterwards. */
    fun ensure(): Boolean {
        val granted = runCatching { repair.ensure() }.getOrElse {
            Log.w(TAG, "Cannot check POST_NOTIFICATIONS", it)
            false
        }
        if (!granted && lastGranted) Log.w(TAG, "POST_NOTIFICATIONS not granted; agent notifications are hidden")
        lastGranted = granted
        return granted
    }

    private companion object {
        const val TAG = "NotificationPermission"
    }
}

/** Grant only when missing and only as Device Owner; verify the actual grant instead of assuming DPM succeeded. */
internal class NotificationPermissionRepair(
    private val sdkInt: Int,
    private val isOwner: () -> Boolean,
    private val isGranted: () -> Boolean,
    private val grant: () -> Boolean,
) {
    fun ensure(): Boolean {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU || isGranted()) return true
        if (isOwner()) runCatching { grant() }
        return isGranted()
    }
}
