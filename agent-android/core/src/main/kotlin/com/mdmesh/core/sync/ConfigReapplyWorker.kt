package com.mdmesh.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mdmesh.core.config.ConfigApplier
import com.mdmesh.core.kiosk.KioskApplier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * After boot / self-update, re-assert the last fully-applied desired-state document BEFORE the first check-in,
 * so restrictions and kiosk are back even if the network is not. No network constraint; runs once.
 * First drops a stale kiosk volume lock (it survives reboots and mutes the device) when no kiosk asks for it;
 * [KioskApplier] serializes that with kiosk enter/exit, so a concurrent config.apply or kiosk.enter keeps its lock.
 */
@HiltWorker
class ConfigReapplyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val applier: ConfigApplier,
    private val kiosk: KioskApplier,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { kiosk.releaseStaleVolumeLock() }
            .onFailure { Log.w(TAG, "stale volume lock release failed", it) }
        runCatching { applier.reapplyPersisted() }
            .onFailure { Log.w(TAG, "config re-apply failed", it) }
        return Result.success() // never retry-storm: the next check-in reconciles anyway
    }

    companion object {
        private const val TAG = "ConfigReapplyWorker"
        fun scheduleNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "config-reapply", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ConfigReapplyWorker>().build(),
            )
        }
    }
}
