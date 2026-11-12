package com.mdmesh.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic check-in driven by WorkManager so it survives reboots/process death and
 * gets battery-friendly backoff for free. Hilt-injected via [HiltWorker] so it can
 * reach [CheckInCoordinator] and the whole capability graph.
 */
@HiltWorker
class CheckInWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: CheckInCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        coordinator.runOnce()
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { t ->
            Log.w(TAG, "check-in failed", t)
            // Terminal server rejections (burned/expired token, enrollment off) can never succeed
            // by retrying — fail so WorkManager doesn't hammer the enroll endpoint. Everything else
            // (IO/timeout/5xx, and "no enrollment code" — the token may be persisted
            // milliseconds later by the compliance activity) retries with exponential backoff.
            if (isTerminalEnrollmentRejection(t)) Result.failure() else Result.retry()
        },
    )

    private fun isTerminalEnrollmentRejection(t: Throwable): Boolean {
        if (t !is EnrollmentException) return false
        val message = t.message ?: return false
        return TERMINAL_ENROLL_MARKERS.any { message.contains(it) }
    }

    companion object {
        private const val TAG = "CheckInWorker"
        private const val UNIQUE_NAME = "mdm-checkin"
        private const val INTERVAL_MINUTES = 15L

        /** Server envelope messages (passed through [EnrollmentException]) that mean "never retry". */
        private val TERMINAL_ENROLL_MARKERS =
            listOf("token.used", "token.invalid", "token.expired", "enrollment.disabled")

        /** Enqueue the periodic check-in. Idempotent (KEEP existing schedule). */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<CheckInWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Run a single check-in as soon as the network allows — used right after
         *  enrollment/provisioning AND after a self-update, so a device that updated itself
         *  re-checks-in in well under a minute rather than waiting on the 15-min floor, even when
         *  starting the foreground service from a background receiver is blocked on Android 12+.
         *  A plain one-time job (no expedited) keeps it safe on minSdk 24 — WorkManager runs it
         *  promptly once the network constraint is met. */
        fun scheduleNow(context: Context) {
            val request = immediateRequest()

            // KEEP, not REPLACE: replacing cancels an in-flight run, which can kill an enroll
            // AFTER the single-use token was POSTed but before credentials were persisted.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$UNIQUE_NAME-now",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Queue a check-in after any current immediate run finishes. This is for state changes
         * that occur while a check-in can already be sending its earlier snapshot: [scheduleNow]
         * would drop this update. It never cancels a running check-in (that could interrupt
         * enrollment); it only replaces a chain that already failed or was cancelled.
         */
        fun scheduleAfterCurrent(context: Context) {
            val request = immediateRequest()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "$UNIQUE_NAME-now",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        /** A one-time check-in that waits for a network; shared by [scheduleNow] and [scheduleAfterCurrent]. */
        private fun immediateRequest() = OneTimeWorkRequestBuilder<CheckInWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
    }
}
