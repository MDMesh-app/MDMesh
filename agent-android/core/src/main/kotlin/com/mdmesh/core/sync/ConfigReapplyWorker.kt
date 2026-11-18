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
import com.mdmesh.core.store.AgentDataStores
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.ConfigApplyResult
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.EventType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * After boot / self-update, re-assert the last fully-applied desired-state document BEFORE the first check-in,
 * so restrictions and kiosk are back even if the network is not. No network constraint; runs once.
 * First reads every store ([AgentDataStores.check]: a corrupt one is recovered now, before the kiosk is settled from
 * it) and drops a stale kiosk volume lock (it survives reboots and mutes the device) when no kiosk asks for it;
 * [KioskApplier] serializes that with kiosk enter/exit, so a concurrent config.apply or kiosk.enter keeps its lock.
 */
@HiltWorker
class ConfigReapplyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val applier: ConfigApplier,
    private val kiosk: KioskApplier,
    private val stores: AgentDataStores,
    private val events: EventSink,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val failures = ReapplySteps(events).run(
            "storage check" to suspend {
                stores.check().takeIf { it.isNotEmpty() }?.joinToString(prefix = "unreadable: ")
            },
            // An on-device or remote exit cut off by a reboot is finished first, so the re-apply below doesn't build
            // on it.
            "interrupted kiosk exit" to suspend { kiosk.resumeInterruptedExit(); null },
            "stale volume lock" to suspend { kiosk.releaseStaleVolumeLock(); null },
            "config re-apply" to suspend { applier.reapplyPersisted()?.let(ReapplySteps::failedOutcomes) },
        )
        if (failures.isNotEmpty()) Log.w(TAG, "re-apply failed in part: $failures")
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

/**
 * The re-apply's steps, each run even when one before it failed. A step that throws or reports a failure is recorded
 * as a [EventType.CONFIG_REAPPLY_FAILED] event, so the console shows it; it used to go to logcat only.
 */
internal class ReapplySteps(private val events: EventSink) {

    /** Runs each step (a failure, or null); returns the failures as recorded. */
    suspend fun run(vararg steps: Pair<String, suspend () -> String?>): List<String> =
        steps.mapNotNull { (name, step) ->
            val failure = runCatching { step() }.getOrElse {
                if (it is CancellationException) throw it
                it.message ?: it.javaClass.simpleName
            }
            failure?.let { "$name: $it" }?.also { detail ->
                runCatching { events.record(EventType.CONFIG_REAPPLY_FAILED, detail) }
            }
        }

    companion object {
        /** The failed outcomes of a re-applied document ("kiosk: failed: …"), or null when none failed. */
        fun failedOutcomes(result: ConfigApplyResult): String? =
            result.outcomes.filterValues(ConfigOutcome::isFailed).entries
                .joinToString("; ") { (key, outcome) -> "$key $outcome" }
                .takeIf { it.isNotEmpty() }
    }
}
