package com.mdmesh.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.policy.factoryreset.FactoryResetWipeHold
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Ends a wipe hold at its deadline when nothing else reads it (#222, PR #227 review): the wipe call returned, no
 * reboot came, and the device went offline, so no command or apply would notice the expiry and Factory reset: Block
 * would stay off. Scheduled by [FactoryResetWipeHold.hold]; WorkManager keeps it across process death.
 *
 * Reading the hold ends an expired hold once (the read that comes first reports `wipeNotStarted` and runs
 * [holdEnded]). Early (WorkManager's delay is not on the uptime clock), it runs again for the time left. Not held and
 * not ended by this read, it runs [holdEnded] itself: that covers a hold that ended in a process killed before it
 * could, a hold without a boot count that died with its process, and a reboot. Both steps are idempotent.
 */
@HiltWorker
class WipeHoldExpiryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val hold: FactoryResetWipeHold,
    private val config: ConfigStateStore,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val reading = hold.read()
        val left = reading.remainingMs
        when {
            // Appended, not replaced: replacing would cancel this run.
            left != null -> enqueue(applicationContext, left, ExistingWorkPolicy.APPEND_OR_REPLACE)
            !reading.endedNow -> holdEnded(applicationContext, config) // ended by this read: onExpired ran it
        }
        return Result.success()
    }

    companion object {
        internal const val UNIQUE_NAME = "wipe-hold-expiry"
        private const val ENQUEUE_TIMEOUT_S = 5L
        private const val TAG = "WipeHoldExpiryWorker"

        /**
         * A wipe hold ended with no reset: re-applies the configuration on the device, which puts Factory reset: Block
         * back now, and marks it stale, so the server re-sends it too. The re-send backs up a lost re-apply and records
         * a clean result over one a config.apply saved with the block deferred during the hold ("some settings
         * deferred"); it applies once and saves its revision, so it doesn't repeat. Only schedules and writes the
         * store: it runs inside a read of the hold, possibly under the configuration lock. Each step on its own.
         */
        fun holdEnded(context: Context, config: ConfigStateStore) {
            runCatching { ConfigReapplyWorker.scheduleAfterHold(context) }
                .onFailure { Log.w(TAG, "re-apply not scheduled", it) }
            runCatching { config.markStale() }
                .onFailure { Log.w(TAG, "configuration not marked stale", it) }
        }

        /**
         * A check at the deadline of a hold set now; replaces one left by an earlier hold. Waits until WorkManager has
         * saved it, so a process killed right after the block is lifted still gets the check.
         */
        fun schedule(context: Context) {
            enqueue(context, FactoryResetWipeHold.MAX_HOLD_MS, ExistingWorkPolicy.REPLACE)
                .result.get(ENQUEUE_TIMEOUT_S, TimeUnit.SECONDS)
        }

        private fun enqueue(context: Context, delayMs: Long, policy: ExistingWorkPolicy) =
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                policy,
                OneTimeWorkRequestBuilder<WipeHoldExpiryWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build(),
            )
    }
}
