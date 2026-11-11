package com.mdmesh.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the heartbeat's work for a broadcast receiver that called `goAsync()`. The broadcast is released ([finish])
 * when the work ends or at [DEADLINE_MS], whichever comes first, so a check-in that waits on the network or runs a
 * long command (an install can take minutes) never holds the broadcast past the system's limit. That would be a
 * background ANR, which usually means a silent kill of the process hosting the service and the install.
 *
 * The work is never cancelled at the deadline: a check-in that is already running commands carries on in [scope].
 * Once the broadcast is released the system no longer keeps the CPU awake for it, so from just before the release
 * until the work ends it holds its own wakelock ([keepAwake], bounded by [WAKELOCK_TIMEOUT_MS]); otherwise a download
 * or install pushed to an idle device would stall as soon as it suspends.
 */
object Heartbeat {
    /** Well under the ~60s a background broadcast may take, with room for a cold location fix plus the round-trip. */
    const val DEADLINE_MS = 40_000L
    /** As long as an install may wait for the platform's result. */
    const val WAKELOCK_TIMEOUT_MS = 10 * 60_000L

    fun launch(
        scope: CoroutineScope,
        onError: (Exception) -> Unit,
        onLate: () -> Unit,
        finish: () -> Unit,
        keepAwake: (timeoutMs: Long) -> () -> Unit = { {} },
        work: suspend () -> Unit,
    ): Job {
        val job = scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
        scope.launch {
            var release: (() -> Unit)? = null
            try {
                if (withTimeoutOrNull(DEADLINE_MS) { job.join() } == null) {
                    onLate()
                    release = keepAwake(WAKELOCK_TIMEOUT_MS) // before finish(): no gap without a wakelock
                }
            } finally {
                finish()
            }
            release?.let { job.invokeOnCompletion { _ -> it() } }
        }
        return job
    }

    /** The heartbeat's work: the Wi-Fi self-heal, then a check-in unless one is already running. */
    suspend fun checkIn(coordinator: CheckInCoordinator, log: (String) -> Unit, selfHeal: suspend () -> Unit) {
        selfHeal()
        // Never queue behind a running cycle (an install holds it for minutes): it checks in once more when it ends.
        if (!coordinator.runIfIdle()) log("check-in already running; heartbeat check-in skipped")
    }
}
