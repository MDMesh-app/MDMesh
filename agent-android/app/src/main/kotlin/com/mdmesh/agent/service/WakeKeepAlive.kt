package com.mdmesh.agent.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import java.util.concurrent.TimeUnit

/**
 * Doze-proof reconcile heartbeat. WorkManager's periodic floor gets deferred in deep Doze, and a
 * parked device's WebSocket can be suspended — so we also schedule an [AlarmManager] alarm with
 * `setAndAllowWhileIdle`, which fires during Doze maintenance windows (throttled to ~once / 9 min)
 * WITHOUT needing the exact-alarm permission. Each firing triggers a check-in (see
 * [KeepAliveReceiver]) and reschedules the next one.
 */
object WakeKeepAlive {

    private const val REQUEST_CODE = 0x57414B45 // "WAKE"
    private val INTERVAL_MS = TimeUnit.MINUTES.toMillis(10)

    /** Schedule the next heartbeat. Idempotent (FLAG_UPDATE_CURRENT replaces any pending alarm). */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        // Inexact and allowed while idle: fires in Doze maintenance windows without the exact-alarm permission.
        arm(SystemClock.elapsedRealtime()) { type, triggerAt -> am.setAndAllowWhileIdle(type, triggerAt, pi) }
    }

    /**
     * Picks the clock, trigger time and Doze allowance for the next heartbeat; [set] schedules it. The elapsed clock,
     * not wall time: a device that boots with a wrong clock which NTP then moves back would otherwise wait out the
     * correction. [now] is `elapsedRealtime()`. An alarm an older agent set on the wall clock uses the same
     * PendingIntent, so the first schedule here replaces it.
     */
    @VisibleForTesting
    internal fun arm(now: Long, set: (type: Int, triggerAt: Long) -> Unit) {
        set(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + INTERVAL_MS)
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, KeepAliveReceiver::class.java)
        // Alarm broadcast carries no result extras, so IMMUTABLE is correct here.
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
