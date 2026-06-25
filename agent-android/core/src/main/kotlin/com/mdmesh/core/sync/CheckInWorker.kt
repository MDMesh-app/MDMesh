package com.mdmesh.core.sync

import android.content.Context
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
        // Retry so WorkManager applies exponential backoff on transient failures.
        onFailure = { Result.retry() },
    )

    companion object {
        private const val UNIQUE_NAME = "mdm-checkin"
        private const val INTERVAL_MINUTES = 15L

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
            val request = OneTimeWorkRequestBuilder<CheckInWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "$UNIQUE_NAME-now",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
