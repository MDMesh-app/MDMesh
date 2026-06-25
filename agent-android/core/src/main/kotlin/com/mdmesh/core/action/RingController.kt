package com.mdmesh.core.action

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Plays a loud locate tone even when silent; auto-stops after a duration. Single active ring. */
@Singleton
class RingController @Inject constructor(@ApplicationContext private val context: Context) {

    private var ringtone: Ringtone? = null
    private var savedVolume: Int = -1
    private val main = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    @Synchronized
    fun start(durationMs: Long) {
        stop()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(
            AudioManager.STREAM_ALARM,
            am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0,
        )
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context, uri).apply {
            @Suppress("DEPRECATION")
            streamType = AudioManager.STREAM_ALARM
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isLooping = true
            }
            play()
        }
        main.postDelayed(autoStop, durationMs)
    }

    @Synchronized
    fun stop() {
        main.removeCallbacks(autoStop)
        ringtone?.stop()
        ringtone = null
        if (savedVolume >= 0) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            savedVolume = -1
        }
    }
}
