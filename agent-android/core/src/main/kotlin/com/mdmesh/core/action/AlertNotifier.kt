package com.mdmesh.core.action

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mdmesh.policy.wifi.DpmHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Shows an admin alert. False when the device can't show it, so the command fails instead of a silent done. */
fun interface AlertSink {
    fun show(title: String, body: String): Boolean
}

/** Posts an admin alert as a high-priority heads-up notification. */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val handle: DpmHandle,
) : AlertSink {

    override fun show(title: String, body: String): Boolean {
        ensureNotificationPermission()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "MDMesh alerts", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        // Without POST_NOTIFICATIONS (Android 13+), or with notifications or this channel turned off, notify() is
        // dropped without an error.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() || channelBlocked(nm)) return false
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(ALERT_ID, n)
        return true
    }

    /**
     * As Device Owner, grant POST_NOTIFICATIONS (Android 13+) when it is missing. A QR-enrolled agent never opens
     * MainActivity, the only other place that grants it, so without this its alerts never show. Also covers devices
     * enrolled before provisioning granted it, without re-enrolling them.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return
        runCatching {
            if (handle.dpm.isDeviceOwnerApp(context.packageName)) {
                handle.dpm.setPermissionGrantState(
                    handle.admin, context.packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }
    }

    private fun channelBlocked(nm: NotificationManager): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE

    private companion object {
        const val CHANNEL = "mdm_alert"
        const val ALERT_ID = 0x4D44 // 'MD'
    }
}
