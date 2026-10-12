package com.mdmesh.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mdmesh.core.sync.CheckInWorker
import com.mdmesh.core.sync.ConfigReapplyWorker
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.proto.EventType

/**
 * Restarts the wake-to-sync foreground service after a reboot or a self-update, so a parked device
 * re-establishes its channel without anyone opening the app. WorkManager reschedules its own 15-min
 * floor across reboots; this restores the instant channel sooner.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching { EventLog(context).record(EventType.BOOT) }
        }
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Re-assert the persisted desired state first (works offline); the check-in below reconciles any drift.
                runCatching { ConfigReapplyWorker.scheduleNow(context) }
                // Enqueue a check-in via WorkManager FIRST: this reliably runs from the background
                // (including right after a self-update), so connectivity resumes in seconds even
                // when starting the foreground service from a background receiver is blocked on
                // Android 12+. The FGS start below is best-effort (restores the instant socket when
                // the OS allows it from this broadcast).
                runCatching { CheckInWorker.scheduleNow(context) }
                // The service starts as specialUse on API 34+ (see the manifest note), which —
                // unlike dataSync — Android 15 permits from BOOT_COMPLETED. runCatching stays as
                // the belt-and-braces: if an OEM still refuses, the worker above covers.
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, CheckInService::class.java),
                    )
                }
                // Re-arm the doze-proof heartbeat (AlarmManager alarms don't survive reboot).
                runCatching { WakeKeepAlive.schedule(context) }
            }
        }
    }
}
