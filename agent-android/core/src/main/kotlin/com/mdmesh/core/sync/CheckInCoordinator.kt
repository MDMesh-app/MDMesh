package com.mdmesh.core.sync

import com.mdmesh.core.capability.CapabilitySource
import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.install.InstallIntegrityPolicy
import com.mdmesh.core.net.MdmApi
import com.mdmesh.core.state.DeviceStateSource
import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.core.telemetry.TelemetrySource
import com.mdmesh.proto.AgentCheckInRequest
import com.mdmesh.proto.EventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {

    private val mutex = Mutex()

    suspend fun runOnce(): Unit = mutex.withLock {
        try {
            cycle()
        } catch (t: Throwable) {
            if (t !is CancellationException) syncStatus.recordFailure(t)
            throw t
        }
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
