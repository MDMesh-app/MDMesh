package com.mdmesh.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mdmesh.core.config.ConfigApplier
import com.mdmesh.core.sync.CheckInCoordinator
import com.mdmesh.core.sync.SyncStatus
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
 *
 * It fires with or without a network, so it is also where a `wifiRadio` Off after which the device could not reach
 * the server is undone ([ConfigApplier.selfHealWifiRadio]). Wi-Fi still has to connect after that, so a healed
 * device usually reaches the server on a later check-in, not this one.
 */
@AndroidEntryPoint
class KeepAliveReceiver : BroadcastReceiver() {

    @Inject lateinit var coordinator: CheckInCoordinator
    @Inject lateinit var configApplier: ConfigApplier
    @Inject lateinit var syncStatus: SyncStatus

    override fun onReceive(context: Context, intent: Intent) {
        // Re-arm the next alarm BEFORE the check-in (FLAG_UPDATE_CURRENT makes it idempotent):
        // if the check-in hangs or the process is killed mid-flight, the heartbeat chain survives.
        WakeKeepAlive.schedule(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { configApplier.selfHealWifiRadio { syncStatus.lastSuccessAtMs } }
                    .onFailure { Log.w(TAG, "wifi radio self-heal check failed", it) }
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
