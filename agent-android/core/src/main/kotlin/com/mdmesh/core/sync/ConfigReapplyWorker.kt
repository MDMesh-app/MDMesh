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
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.ConfigApplyResult
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.EventType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Re-asserts the newest delivered desired-state document with no network: after boot / self-update BEFORE the first
 * check-in, so restrictions and kiosk are back even if the network is not, and when a wipe hold ends mid-uptime
 * ([scheduleAfterHold]), which puts Factory reset: Block back. No network constraint; runs once.
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
                stores.check().takeIf { it.isNotEmpty() }?.joinToString()
            },
            // An on-device or remote exit cut off by a reboot is finished first, so the re-apply below doesn't build
            // on it.
            "interrupted kiosk exit" to suspend { kiosk.resumeInterruptedExit(); null },
            // Before anything else touches the kiosk: a guard cut off mid-arm or mid-disarm (or an agent updated
            // in kiosk from a version without one) is settled at once, with no server round-trip.
            "kiosk guard" to suspend { (kiosk.reconcileGuard() as? KioskResult.Failed)?.reason },
            // A kiosk the device was taken out of without an exit (an agent update ends lock task) comes back.
            "kiosk return" to suspend { kiosk.returnToKiosk(); null },
            "stale volume lock" to suspend { kiosk.releaseStaleVolumeLock(); null },
            "config re-apply" to suspend { applier.reapplyPersisted()?.let(ReapplySteps::failedOutcomes) },
        )
        if (failures.isNotEmpty()) Log.w(TAG, "re-apply failed in part: $failures")
        return Result.success() // never retry-storm: the next check-in reconciles anyway
    }

    companion object {
        private const val TAG = "ConfigReapplyWorker"
        internal const val UNIQUE_NAME = "config-reapply"
        internal const val AFTER_HOLD_NAME = "config-reapply-after-hold"

        /** A re-apply unless one is waiting or running: for boot and self-update, which a running one already sees. */
        fun scheduleNow(context: Context) = enqueue(context, UNIQUE_NAME, ExistingWorkPolicy.KEEP)

        /**
         * The re-apply for a wipe hold that ended. A re-apply already running may have deferred the block while the
         * hold was on, so KEEP on its name would drop this one (PR #227 review). Under its own name, a running boot
         * re-apply that fails or is cancelled can't take it down with it; the configuration lock in [ConfigApplier]
         * still orders it after any apply that deferred the block. Appended to one of its own that is running.
         */
        fun scheduleAfterHold(context: Context) =
            enqueue(context, AFTER_HOLD_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE)

        private fun enqueue(context: Context, name: String, policy: ExistingWorkPolicy) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                name, policy, OneTimeWorkRequestBuilder<ConfigReapplyWorker>().build(),
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
