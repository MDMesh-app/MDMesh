package com.mdmesh.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                coordinator.runOnce()
            } catch (_: Exception) {
                // transient — the next heartbeat retries
            } finally {
                WakeKeepAlive.schedule(context)
                pending.finish()
            }
        }
    }
}
