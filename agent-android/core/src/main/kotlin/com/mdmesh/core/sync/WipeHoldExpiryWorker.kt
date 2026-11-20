package com.mdmesh.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mdmesh.policy.factoryreset.FactoryResetWipeHold
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Ends a wipe hold at its deadline when nothing else reads it (#222, PR #227 review): the wipe call returned, no
 * reboot came, and the device went offline, so no command or apply would notice the expiry and Factory reset: Block
 * would stay off. Scheduled by [FactoryResetWipeHold.hold]; WorkManager keeps it across process death.
 *
 * Reading [FactoryResetWipeHold.remainingMs] ends an expired hold once (the read that comes first reports
 * `wipeNotStarted` and schedules [ConfigReapplyWorker]). Early (WorkManager's delay is not on the uptime clock), it
 * runs again for the time left. Not held, it schedules the re-apply itself: that also covers a hold that ended in a
 * process killed before it could schedule the re-apply, a hold without a boot count that died with its process, and
 * a reboot. The re-apply is idempotent and runs after any re-apply already running, which may have deferred the block
 * while the hold was on.
 */
@HiltWorker
class WipeHoldExpiryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val hold: FactoryResetWipeHold,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val left = hold.remainingMs
        if (left == null) {
            ConfigReapplyWorker.scheduleAfterCurrent(applicationContext)
        } else {
            // Appended, not replaced: replacing would cancel this run.
            enqueue(applicationContext, left, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }

    companion object {
        internal const val UNIQUE_NAME = "wipe-hold-expiry"
        private const val ENQUEUE_TIMEOUT_S = 5L

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
