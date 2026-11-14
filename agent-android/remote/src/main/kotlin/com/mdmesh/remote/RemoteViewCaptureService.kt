@file:Suppress("ComplexCondition", "MaxLineLength")

package com.mdmesh.remote

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Foreground owner for the active MediaProjection; it never survives process restart. */
class RemoteViewCaptureService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundForCapture()
        val id = intent?.getStringExtra(EXTRA_SESSION_ID)
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.intentExtra(EXTRA_RESULT_DATA)
        val session = RemoteViewRuntime.session
        if (id == null || data == null || code != Activity.RESULT_OK || session == null) { stopSelf(startId); return START_NOT_STICKY }
        CoroutineScope(Dispatchers.IO).launch {
            session.startCapture(id, data).onFailure { stopSelf(startId) }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundForCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "MDMesh screen sharing", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("MDMesh screen sharing")
            .setContentText("An administrator is viewing this device screen.")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(ID, notification)
    }
    override fun onDestroy() { RemoteViewRuntime.session?.serviceDestroyed(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    @Suppress("DEPRECATION") private fun Intent.intentExtra(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(key, Intent::class.java) else getParcelableExtra(key)
    companion object { const val EXTRA_SESSION_ID="com.mdmesh.remote.SESSION"; const val EXTRA_RESULT_CODE="com.mdmesh.remote.RESULT_CODE"; const val EXTRA_RESULT_DATA="com.mdmesh.remote.RESULT_DATA"; private const val CHANNEL="mdmesh_remote_view"; private const val ID=7302 }
}

/** Process-local service bridge. A remote view is explicitly non-restorable after process death. */
object RemoteViewRuntime { @Volatile var session: AndroidRemoteViewSession? = null }
