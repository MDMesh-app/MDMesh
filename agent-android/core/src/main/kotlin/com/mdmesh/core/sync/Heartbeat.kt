package com.mdmesh.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Runs the heartbeat's work for a broadcast receiver that called `goAsync()`. The broadcast is released
 * ([Hooks.finish]) when the work ends or at [DEADLINE_MS], whichever comes first, so a check-in that waits on the
 * network or runs a long command (an install can take minutes) never holds the broadcast past the system's limit.
 * That would be a background ANR, which usually means a silent kill of the process hosting the service and the
 * install.
 *
 * The work is never cancelled at the deadline: a check-in that is already running commands carries on in its scope.
 * Once the broadcast is released the system no longer keeps the CPU awake for it, so from just before the release
 * until the work ends it holds its own wakelock ([Hooks.keepAwake], bounded by [WAKELOCK_TIMEOUT_MS]); otherwise a
 * download or install pushed to an idle device would stall as soon as it suspends.
 */
object Heartbeat {
    /** Well under the ~60s a background broadcast may take, with room for a cold location fix plus the round-trip. */
    const val DEADLINE_MS = 40_000L
    /** As long as an install may wait for the platform's result. */
    const val WAKELOCK_TIMEOUT_MS = 10 * 60_000L
    /** A heartbeat makes up for an unserved hand-off only this soon after it started: its wakelock lasts 10:40. */
    const val RETRY_BUDGET_MS = 8 * 60_000L

    /** What a heartbeat reports to, and asks of, the receiver that runs it. */
    class Hooks(
        /** The work, or taking the wakelock, failed: logged; the next heartbeat retries. */
        val onError: (Exception) -> Unit,
        /** The work is still running at [DEADLINE_MS]. */
        val onLate: () -> Unit,
        /** Releases the broadcast (`PendingResult.finish()`). */
        val finish: () -> Unit,
        /** Takes a wakelock that ends itself after the given milliseconds at the latest; returns its release. */
        val keepAwake: (timeoutMs: Long) -> () -> Unit = { {} },
    )

    // The top-level guard of a broadcast's work: whatever it throws (network, server, platform) is reported and the
    // next heartbeat retries; nothing may escape into the agent's scope, where it would end the process.
    @Suppress("TooGenericExceptionCaught")
    fun launch(scope: CoroutineScope, hooks: Hooks, work: suspend () -> Unit): Job {
        val job = scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hooks.onError(e)
            }
        }
        scope.launch {
            var release: (() -> Unit)? = null
            try {
                if (withTimeoutOrNull(DEADLINE_MS) { job.join() } == null) {
                    hooks.onLate()
                    // Before finish(): no gap without a wakelock. A refusal (no WAKE_LOCK permission) is reported,
                    // never thrown into the agent's scope (it would end the process).
                    release = try {
                        hooks.keepAwake(WAKELOCK_TIMEOUT_MS)
                    } catch (e: SecurityException) {
                        hooks.onError(e)
                        null
                    }
                }
            } finally {
                hooks.finish()
            }
            release?.let { job.invokeOnCompletion { _ -> it() } }
        }
        return job
    }

    /**
     * The heartbeat's work: the Wi-Fi self-heal, then a check-in. If one is already running it hands the request to
     * it and waits for the check-in that serves it, so that check-in also runs under the heartbeat's wakelock. [clock]
     * is monotonic milliseconds.
     */
    suspend fun checkIn(
        coordinator: CheckInCoordinator,
        log: (String) -> Unit,
        clock: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
        selfHeal: suspend () -> Unit,
    ) {
        val start = clock()
        selfHeal()
        // Never queue behind a running cycle (an install holds it for minutes): it checks in for us when it can.
        when (coordinator.runOrHandOff()) {
            CheckInOutcome.RAN -> Unit
            CheckInOutcome.SERVED -> log("check-in already running; handed to it")
            CheckInOutcome.NOT_SERVED ->
                // That cycle failed first, was cancelled (a stopped worker) or hit its cap. Make up for it once, while
                // the wakelock still has time to cover a check-in; otherwise the next heartbeat is 10 minutes away.
                if (clock() - start < RETRY_BUDGET_MS) {
                    log("handed-off check-in did not run; checking in now")
                    if (coordinator.runOrHandOff() == CheckInOutcome.NOT_SERVED) {
                        log("check-in did not run again; the next heartbeat retries")
                    }
                } else {
                    log("handed-off check-in did not run; the next heartbeat retries")
                }
        }
    }
}
