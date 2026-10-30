package com.mdmesh.core.action

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Shows an admin alert to the device user. */
fun interface AlertSink {
    /** False when the device can't show it, so the command fails instead of a silent done. */
    fun show(title: String, body: String): Boolean
}

/** Posts an admin alert as a high-priority heads-up notification. */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permission: NotificationPermissionAccess,
) : AlertSink {

    override fun show(title: String, body: String): Boolean {
        permission.ensure()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "MDMesh alerts", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        // Without POST_NOTIFICATIONS (Android 13+), or with notifications or this channel turned off, notify() is
        // dropped without an error.
        val channelImportance =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.getNotificationChannel(CHANNEL)?.importance else null
        if (!alertVisible(NotificationManagerCompat.from(context).areNotificationsEnabled(), channelImportance)) {
            return false
        }
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

    private companion object {
        const val CHANNEL = "mdm_alert"
        const val ALERT_ID = 0x4D44 // 'MD'
    }
}

/** App notifications on and the alert channel not blocked; null importance means no channels (below Android 8). */
internal fun alertVisible(appEnabled: Boolean, channelImportance: Int?): Boolean =
    appEnabled && channelImportance != NotificationManager.IMPORTANCE_NONE
