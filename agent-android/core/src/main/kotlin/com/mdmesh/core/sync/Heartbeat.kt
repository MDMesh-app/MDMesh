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
 */
object Heartbeat {
    /** Well under the ~60s a background broadcast may take, with room for a cold location fix plus the round-trip. */
    const val DEADLINE_MS = 40_000L

    fun launch(
        scope: CoroutineScope,
        onError: (Exception) -> Unit,
        onLate: () -> Unit,
        finish: () -> Unit,
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
            try {
                if (withTimeoutOrNull(DEADLINE_MS) { job.join() } == null) onLate()
            } finally {
                finish()
            }
        }
        return job
    }
}
