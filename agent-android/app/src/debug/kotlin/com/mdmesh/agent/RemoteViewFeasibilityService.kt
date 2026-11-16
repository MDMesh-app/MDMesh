package com.mdmesh.agent

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.mdmesh.remote.RemoteViewProbePlatform

/**
 * Debug-only projection owner. It discards frames rather than encoding or
 * transmitting them; its 30-second lifetime proves the consent and foreground
 * service path without creating a remotely usable feature.
 */
class RemoteViewFeasibilityService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var capturedFrameCount = 0

    private val stopRunnable = Runnable { stopProbe() }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = stopProbe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = intent?.intentExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForegroundForProbe()
        runCatching {
            val manager = getSystemService(MediaProjectionManager::class.java)
            manager.getMediaProjection(resultCode, resultData)?.also(::startProjection)
                ?: error("MediaProjection was unavailable after consent")
        }.onFailure {
            RemoteViewFeasibilityProbe.markFailed()
            stopProbe()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(newProjection: MediaProjection) {
        if (projection != null) return
        projection = newProjection
        newProjection.registerCallback(projectionCallback, mainHandler)
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                capturedFrameCount += 1
                if (capturedFrameCount == 1 || capturedFrameCount % FRAME_DIAGNOSTIC_INTERVAL == 0) {
                    val brightness = sampledBrightness(image)
                    RemoteViewFeasibilityProbe.markCapturedFrame(capturedFrameCount, brightness)
                }
            } finally {
                image.close()
            }
        }, mainHandler)
        imageReader = reader
        display = newProjection.createVirtualDisplay(
            "MDMesh remote-view feasibility probe",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler,
        )
        RemoteViewFeasibilityProbe.markActive()
        mainHandler.postDelayed(stopRunnable, PROBE_DURATION_MS)
    }

    private fun startForegroundForProbe() {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MDMesh screen-share test")
            .setContentText("Testing local screen-capture consent; no video is sent.")
            .setOngoing(true)
            .build()
        if (RemoteViewProbePlatform.usesTypedForegroundService(Build.VERSION.SDK_INT)) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MDMesh screen-share test", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun stopProbe() {
        mainHandler.removeCallbacks(stopRunnable)
        display?.release()
        display = null
        imageReader?.close()
        imageReader = null
        val activeProjection = projection
        projection = null
        activeProjection?.unregisterCallback(projectionCallback)
        activeProjection?.stop()
        RemoteViewFeasibilityProbe.markStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopProbe()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Samples a small grid rather than retaining a frame. All RGBA channels are
     * averaged, so the result is independent of the platform's channel order.
     */
    private fun sampledBrightness(image: android.media.Image): Int {
        val plane = image.planes.firstOrNull() ?: return 0
        val buffer = plane.buffer
        var total = 0L
        var samples = 0
        for (vertical in SAMPLE_GRID.indices) {
            val y = ((image.height - 1) * SAMPLE_GRID[vertical]).toInt().coerceAtLeast(0)
            for (horizontal in SAMPLE_GRID.indices) {
                val x = ((image.width - 1) * SAMPLE_GRID[horizontal]).toInt().coerceAtLeast(0)
                val offset = y * plane.rowStride + x * plane.pixelStride
                if (offset + 2 >= buffer.limit()) continue
                total += (buffer.get(offset).toInt() and 0xff)
                total += (buffer.get(offset + 1).toInt() and 0xff)
                total += (buffer.get(offset + 2).toInt() and 0xff)
                samples += 3
            }
        }
        return if (samples == 0) 0 else (total / samples).toInt()
    }

    @Suppress("DEPRECATION")
    private fun Intent.intentExtra(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(key, Intent::class.java)
        else getParcelableExtra(key)

    companion object {
        const val EXTRA_RESULT_CODE = "com.mdmesh.remote.probe.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.mdmesh.remote.probe.RESULT_DATA"
        private const val CHANNEL_ID = "mdmesh_remote_view_probe"
        private const val NOTIFICATION_ID = 7_301
        private const val PROBE_DURATION_MS = 30_000L
        private const val FRAME_DIAGNOSTIC_INTERVAL = 10
        private val SAMPLE_GRID = floatArrayOf(0.15f, 0.5f, 0.85f)
    }
}
