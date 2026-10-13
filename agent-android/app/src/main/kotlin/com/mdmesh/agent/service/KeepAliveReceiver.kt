package com.mdmesh.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mdmesh.core.sync.CheckInCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fired by the [WakeKeepAlive] doze-proof alarm. Does a single check-in (delivering any pending
 * commands during the Doze maintenance window) and reschedules the next alarm. Uses `goAsync` so
 * the short network round-trip completes; long-running commands are still handled by the foreground
 * service / its WebSocket when the device is active.
 *
 * A check-in here does NOT require starting a foreground service, so it sidesteps the Android 12+
 * background-FGS-start restriction.
 */
@AndroidEntryPoint
class KeepAliveReceiver : BroadcastReceiver() {

    @Inject lateinit var coordinator: CheckInCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        // Re-arm the next alarm BEFORE the check-in (FLAG_UPDATE_CURRENT makes it idempotent):
        // if the check-in hangs or the process is killed mid-flight, the heartbeat chain survives.
        WakeKeepAlive.schedule(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                coordinator.runOnce()
            } catch (e: Exception) {
                Log.w(TAG, "heartbeat check-in failed", e) // transient — the next heartbeat retries
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "KeepAliveReceiver"
    }
}
