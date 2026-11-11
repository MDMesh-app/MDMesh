package com.mdmesh.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
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
 * check-in ends or at [Heartbeat.DEADLINE_MS]; a longer check-in carries on in the agent's scope under a bounded
 * wakelock. When a check-in is already running it hands the request to it (that one checks in once more when it
 * ends) instead of waiting for the lock.
 *
 * The check-in itself does not depend on the foreground service. The receiver also restarts that service if Android
 * stopped it (allowed from the background for a Device Owner; a refusal changes nothing else).
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
        // Bring the foreground service back if an OEM killed it: it keeps this process (and any install the check-in
        // below starts) from being frozen or killed once the broadcast is released, and restores the instant channel.
        // Started "from heartbeat": no check-in of its own (the one below is it) and no grace window. A running
        // service only re-posts its notification; a Device Owner may start it from the background, and where that is
        // refused, nothing else changes.
        CheckInService.start(context, fromHeartbeat = true)
        val pending = goAsync()
        Heartbeat.launch(
            scope = scope,
            hooks = Heartbeat.Hooks(
                onError = { Log.w(TAG, "heartbeat check-in failed", it) }, // transient — the next heartbeat retries
                onLate = { Log.i(TAG, "heartbeat still running at its deadline; broadcast released, it goes on") },
                finish = { pending.finish() },
                keepAwake = { timeoutMs -> wakeLock(context, timeoutMs) },
            ),
        ) {
            Heartbeat.checkIn(coordinator, { Log.i(TAG, it) }) {
                try {
                    configApplier.selfHealWifiRadio { syncStatus.lastSuccessAtMs }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "wifi radio self-heal check failed", e)
                }
            }
        }
    }

    /** A partial wakelock that releases itself after [timeoutMs] at the latest; returns its release. */
    private fun wakeLock(context: Context, timeoutMs: Long): () -> Unit {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mdmesh:heartbeat").apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
        return { runCatching { if (lock.isHeld) lock.release() } }
    }

    private companion object {
        const val TAG = "KeepAliveReceiver"
    }
}
