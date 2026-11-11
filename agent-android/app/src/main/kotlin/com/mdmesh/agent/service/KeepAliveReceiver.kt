package com.mdmesh.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mdmesh.core.config.ConfigApplier
import com.mdmesh.core.di.AgentScope
import com.mdmesh.core.sync.CheckInCoordinator
import com.mdmesh.core.sync.Heartbeat
import com.mdmesh.core.sync.SyncStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/**
 * Fired by the [WakeKeepAlive] doze-proof alarm. Does a single check-in (delivering any pending
 * commands during the Doze maintenance window) and reschedules the next alarm. Uses `goAsync`, released when the
 * check-in ends or at [Heartbeat.DEADLINE_MS]; a longer check-in carries on in the agent's scope. It skips when a
 * check-in is already running.
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
    @Inject @AgentScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        // Re-arm the next alarm BEFORE the check-in (FLAG_UPDATE_CURRENT makes it idempotent):
        // if the check-in hangs or the process is killed mid-flight, the heartbeat chain survives.
        WakeKeepAlive.schedule(context)
        val pending = goAsync()
        Heartbeat.launch(
            scope = scope,
            onError = { Log.w(TAG, "heartbeat check-in failed", it) }, // transient — the next heartbeat retries
            onLate = { Log.i(TAG, "heartbeat still running at its deadline; broadcast released, the check-in goes on") },
            finish = { pending.finish() },
        ) {
            try {
                configApplier.selfHealWifiRadio { syncStatus.lastSuccessAtMs }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "wifi radio self-heal check failed", e)
            }
            // Never queue behind a running cycle (an install holds it for minutes): that cycle is this check-in.
            if (!coordinator.runIfIdle()) Log.i(TAG, "check-in already running; heartbeat check-in skipped")
        }
    }

    private companion object {
        const val TAG = "KeepAliveReceiver"
    }
}
