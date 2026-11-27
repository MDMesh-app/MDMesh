package com.mdmesh.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.kiosk.KioskResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Makes the kiosk guard match the device ([KioskApplier.reconcileGuard]) after lock task was entered or left, whatever
 * entered or left it (the kiosk launcher at boot, a relock, an exit whose disarm had to wait for lock task to end).
 * Off the receiver, under the applier's lock; WorkManager keeps it across a process death. A failure is logged and
 * not retried here: a retry with backoff would hold back the run queued after it (a later lock-task change), so the
 * guard could stay on for minutes after a kiosk ends. Boot, an agent update, `config.apply` and every lock-task change
 * run it again.
 */
@HiltWorker
class KioskGuardWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val kiosk: KioskApplier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val r = kiosk.reconcileGuard()
        if (r is KioskResult.Failed) runCatching { Log.w(TAG, "kiosk guard not settled: ${r.reason}") }
        return Result.success()
    }

    companion object {
        private const val TAG = "KioskGuardWorker"
        const val NAME = "kiosk-guard"

        /**
         * After a lock-task change. Appended to a run in progress, never replacing it: that one may have read the
         * device before this change, so another run follows it.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<KioskGuardWorker>().build(),
            )
        }
    }
}
