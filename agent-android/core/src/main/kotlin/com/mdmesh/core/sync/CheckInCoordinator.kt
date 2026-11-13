package com.mdmesh.core.sync

import com.mdmesh.core.capability.CapabilitySource
import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.di.IoDispatcher
import com.mdmesh.core.install.InstallIntegrityPolicy
import com.mdmesh.core.net.MdmApi
import com.mdmesh.core.state.DeviceStateSource
import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.core.telemetry.TelemetrySource
import com.mdmesh.proto.AgentCheckInRequest
import com.mdmesh.proto.EventType
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One full check-in cycle — the source of truth of the sync loop:
 *
 *  1. ensure the device is enrolled (server-issued id);
 *  2. drain the next batch of pending command acks and POST it with the fresh capabilities (results beyond one
 *     batch, or that the server did not take, wait for the next check-in);
 *  3. dispatch each returned (capability-gated) command;
 *  4. buffer the new results for delivery on the next cycle.
 *
 * [runOnce] is single-flight process-wide (singleton + mutex): the periodic worker, the one-shot
 * worker, the foreground service, the doze heartbeat and WebSocket wakes all race into it, and a
 * fresh device firing several concurrent enrolls burns its single-use token.
 *
 * Network/enroll failures bubble up so [CheckInWorker] applies WorkManager backoff; any
 * drained acks are restored to the buffer so they retry. Per-command failures never abort
 * the batch — they become `failed`/`unsupported`/`expired` results.
 */
@Singleton
@Suppress("LongParameterList") // A DI root: each parameter is one injected collaborator of the sync loop.
class CheckInCoordinator @Inject constructor(
    private val api: MdmApi,
    private val enrollment: EnrollmentManager,
    private val identity: DeviceIdentity,
    private val capabilitySource: CapabilitySource,
    private val dispatcher: CommandDispatcher,
    private val pending: PendingResults,
    private val stateSource: DeviceStateSource,
    private val telemetrySource: TelemetrySource,
    private val eventSink: EventSink,
    private val installIntegrity: InstallIntegrityPolicy,
    private val hardwareIdSource: HardwareIdSource = HardwareIdSource { null },
    private val syncStatus: SyncStatus = SyncStatus(),
    /**
     * Where the cycle runs: never the caller's thread. The foreground service calls [runOnce] from its main-thread
     * scope, and the kiosk HOME shares that thread, so an APK hash, an apps.scan/apps.icons or a multi-MB result
     * batch there would freeze it (and risk an ANR).
     */
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    @VisibleForTesting
    internal val mutex = Mutex()

    // Hand-offs, all guarded by handOffLock. While a holder is open, a runOrHandOff() that finds the lock busy leaves
    // a request instead of waiting for the lock. Before the running cycle has sent its check-in, the request joins
    // that cycle ([current]); after, it waits for one more cycle ([handOff]). A request is served by a check-in made
    // after it that leaves nothing to report: if that cycle ran commands, it moves on to the next cycle, which
    // carries their results. Each request completes with whether it was served.
    private val handOffLock = Any()
    private var holderOpen = false
    private var currentSent = false
    private val current = mutableListOf<CompletableDeferred<Boolean>>()
    private val handOff = mutableListOf<CompletableDeferred<Boolean>>()

    suspend fun runOnce(): Unit = withContext(ioDispatcher) {
        mutex.withLock { holderCycles() }
    }

    /**
     * A check-in without queueing behind one already running. Idle: runs the cycle itself ([CheckInOutcome.RAN]).
     * Busy: hands the request to the running holder and waits until a check-in has been made for it
     * ([CheckInOutcome.SERVED]): the running cycle if it hadn't sent its request yet, else one trailing cycle after
     * it. [CheckInOutcome.NOT_SERVED] when that holder failed, was cancelled or hit its cap first.
     *
     * For the heartbeat: a long cycle (an install holds it for minutes) must not hold its broadcast, yet the
     * install's result should reach the server right after it, under the heartbeat's wakelock.
     */
    suspend fun runOrHandOff(): CheckInOutcome = withContext(ioDispatcher) {
        var outcome: CheckInOutcome? = null
        while (outcome == null) {
            if (mutex.tryLock()) {
                try {
                    holderCycles()
                } finally {
                    mutex.unlock()
                }
                outcome = CheckInOutcome.RAN
                continue
            }
            val request = synchronized(handOffLock) {
                if (!holderOpen) {
                    null
                } else {
                    CompletableDeferred<Boolean>().also { if (currentSent) handOff += it else current += it }
                }
            }
            if (request != null) {
                outcome = if (request.await()) CheckInOutcome.SERVED else CheckInOutcome.NOT_SERVED
            } else {
                // The holder is just opening or just letting go: nothing to hand off to yet, so look again shortly.
                delay(HAND_OFF_RETRY_MS)
            }
        }
        outcome
    }

    /**
     * Caller holds the lock. One cycle, then one trailing cycle per batch of hand-offs still waiting (requests that
     * arrived after a cycle sent, or joined one that then ran commands; several share one), at most
     * [MAX_TRAILING_CYCLES]. Requests past that, and those of a cycle that failed before sending or was cancelled,
     * are let go unserved; the next trigger covers them.
     */
    private suspend fun holderCycles() {
        synchronized(handOffLock) {
            holderOpen = true
            currentSent = false
        }
        try {
            servedCycle()
            var trailing = 0
            while (true) {
                val proceed = synchronized(handOffLock) {
                    when {
                        handOff.isEmpty() -> false
                        trailing >= MAX_TRAILING_CYCLES -> false // let go: the finally releases them unserved
                        else -> {
                            current += handOff // the next cycle serves them, and anyone who asks before it sends
                            handOff.clear()
                            currentSent = false
                            true
                        }
                    }.also { if (!it) holderOpen = false }
                }
                if (!proceed) return
                trailing++
                servedCycle()
            }
        } finally {
            synchronized(handOffLock) {
                holderOpen = false
                (current + handOff).forEach { it.complete(false) }
                current.clear()
                handOff.clear()
            }
        }
    }

    /**
     * One cycle, then its joined requests: served if it ran no commands; moved on to the next cycle if it did (that
     * one reports their results); on a failure, served only if the request had gone out.
     */
    private suspend fun servedCycle() {
        var made = false
        var ranCommands = false
        try {
            ranCommands = recordedCycle()
            made = true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            made = synchronized(handOffLock) { currentSent }
            throw t
        } finally {
            synchronized(handOffLock) {
                if (made && ranCommands) {
                    handOff.addAll(0, current)
                } else {
                    current.forEach { it.complete(made) }
                }
                current.clear()
            }
        }
    }

    /** From here on this cycle's request is (being) sent: later hand-offs need a cycle of their own. */
    private fun sending() = synchronized(handOffLock) { currentSent = true }

    /** Returns whether the cycle ran commands. */
    private suspend fun recordedCycle(): Boolean {
        try {
            return cycle()
        } catch (t: Throwable) {
            if (t !is CancellationException) syncStatus.recordFailure(t)
            throw t
        }
    }

    companion object {
        /** Trailing cycles one holder runs for hand-offs before it lets go. */
        const val MAX_TRAILING_CYCLES = 3
        private const val HAND_OFF_RETRY_MS = 10L
    }

    private suspend fun cycle(): Boolean {
        val deviceId = enrollment.ensureEnrolled()
        val authorization = "Bearer ${identity.secret().orEmpty()}"
        val matrix = capabilitySource.matrix(deviceId)
        val acks = pending.drain()
        val bufferedEvents = eventSink.drain()

        sending()
        val response = try {
            api.checkIn(
                authorization,
                AgentCheckInRequest(
                    deviceId = deviceId,
                    capabilities = matrix.capabilities,
                    results = acks,
                    state = runCatching { stateSource.snapshot() }.getOrNull(),
                    telemetry = runCatching { telemetrySource.snapshot() }.getOrNull(),
                    events = bufferedEvents,
                    hardwareId = runCatching { hardwareIdSource.get() }.getOrNull(),
                ),
            )
        } catch (t: Throwable) {
            pending.restore(acks) // not yet acknowledged by the server; retry next cycle
            eventSink.restore(bufferedEvents)
            throw t
        }

        val data = response.data
        if (!response.isOk || data == null) {
            pending.restore(acks)
            eventSink.restore(bufferedEvents)
            throw CheckInException(response.message ?: "check-in rejected")
        }
        // The server answered: recorded now, not after the commands (an app.install can download for minutes).
        syncStatus.recordSuccess()
        pending.delivered(data.resultsAccepted)
        eventSink.delivered()
        // Before this batch runs: its installs are judged by what this server guarantees.
        installIntegrity.update(data.installIntegrity)

        for (command in data.commands) {
            val result = dispatcher.dispatch(command)
            // Saved one by one: a later command can end the process (an agent self-update does), and the results
            // before it must still reach the server.
            pending.add(listOf(result))
            // Record each command outcome as a timeline event (flushed next cycle).
            eventSink.record(EventType.COMMAND_RESULT, "${result.commandId}:${result.status}")
        }
        return data.commands.isNotEmpty()
    }
}

/** What [CheckInCoordinator.runOrHandOff] did with a request. */
enum class CheckInOutcome {
    /** No check-in was running: it ran one itself. */
    RAN,
    /** It handed the request to the running cycle, which checked in for it. */
    SERVED,
    /** It handed the request over, but no check-in ran for it (that cycle failed, was cancelled or hit its cap). */
    NOT_SERVED,
}

/** A check-in was rejected by the server. Lets the worker retry with backoff. */
class CheckInException(message: String) : Exception(message)
