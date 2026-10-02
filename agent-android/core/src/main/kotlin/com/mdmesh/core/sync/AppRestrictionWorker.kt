package com.mdmesh.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mdmesh.core.config.AppRestrictions
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Offline package protection and recovery; no dependency on a fully-applied config snapshot. */
@HiltWorker
class AppRestrictionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val restrictions: AppRestrictions,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        restrictions.reconcile()
        Result.success()
    }.getOrElse {
        Log.w("AppRestrictionWorker", "App restriction recovery failed", it)
        if (runAttemptCount < MAX_RECOVERY_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        private const val MAX_RECOVERY_ATTEMPTS = 3
        fun scheduleNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "app-restrictions", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<AppRestrictionWorker>().build(),
            )
        }
    }
}
