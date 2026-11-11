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

    private val mutex = Mutex()

    // Hand-offs, guarded by handOffLock. While a holder is open, a runOrHandOff() that finds the lock busy leaves a
    // request instead of waiting for the lock; the holder serves it with one more cycle before it lets go.
    private val handOffLock = Any()
    private var holderOpen = false
    private var handOff: CompletableDeferred<Unit>? = null

    suspend fun runOnce(): Unit = withContext(ioDispatcher) {
        mutex.withLock { holderCycles() }
    }

    /**
     * A check-in without queueing behind one already running. Idle: runs the cycle itself and returns true. Busy:
     * hands the request to the running cycle, which checks in once more when it ends, and returns false once that
     * trailing check-in is done. For the heartbeat: a long cycle (an install holds it for minutes) must not hold its
     * broadcast, yet the install's result should reach the server right after it, under the heartbeat's wakelock.
     */
    suspend fun runOrHandOff(): Boolean = withContext(ioDispatcher) {
        var ranItself: Boolean? = null
        while (ranItself == null) {
            if (mutex.tryLock()) {
                try {
                    holderCycles()
                } finally {
                    mutex.unlock()
                }
                ranItself = true
                continue
            }
            val served = synchronized(handOffLock) {
                if (holderOpen) handOff ?: CompletableDeferred<Unit>().also { handOff = it } else null
            }
            if (served != null) {
                served.await()
                ranItself = false
            } else {
                delay(HAND_OFF_RETRY_MS) // the holder is just opening or just letting go: look again in a moment
            }
        }
        ranItself == true
    }

    /**
     * Caller holds the lock. One cycle, then one trailing cycle per batch of hand-offs that arrived meanwhile (several
     * requests share one), at most [MAX_TRAILING_CYCLES]; requests past that are let go unserved, as are the waiters
     * of a cycle that failed or was cancelled. The next trigger covers them.
     */
    private suspend fun holderCycles() {
        synchronized(handOffLock) { holderOpen = true }
        try {
            recordedCycle()
            var trailing = 0
            while (true) {
                val served = synchronized(handOffLock) {
                    val request = handOff
                    handOff = null
                    if (request == null || trailing >= MAX_TRAILING_CYCLES) holderOpen = false
                    request
                } ?: return
                if (trailing >= MAX_TRAILING_CYCLES) {
                    served.complete(Unit)
                    return
                }
                trailing++
                try {
                    recordedCycle()
                } finally {
                    served.complete(Unit)
                }
            }
        } finally {
            synchronized(handOffLock) {
                holderOpen = false
                handOff?.complete(Unit)
                handOff = null
            }
        }
    }

    private suspend fun recordedCycle() {
        try {
            cycle()
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

    private suspend fun cycle() {
        val deviceId = enrollment.ensureEnrolled()
        val authorization = "Bearer ${identity.secret().orEmpty()}"
        val matrix = capabilitySource.matrix(deviceId)
        val acks = pending.drain()
        val bufferedEvents = eventSink.drain()

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
    }
}

/** A check-in was rejected by the server. Lets the worker retry with backoff. */
class CheckInException(message: String) : Exception(message)
