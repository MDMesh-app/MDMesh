package com.mdmesh.core.sync

import com.mdmesh.core.capability.CapabilitySource
import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.net.MdmApi
import com.mdmesh.core.state.DeviceStateSource
import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.core.telemetry.TelemetrySource
import com.mdmesh.proto.AgentCheckInRequest
import com.mdmesh.proto.EventType
import javax.inject.Inject

/**
 * One full check-in cycle — the source of truth of the sync loop:
 *
 *  1. ensure the device is enrolled (server-issued id);
 *  2. drain pending command acks and POST them together with the fresh capabilities;
 *  3. dispatch each returned (capability-gated) command;
 *  4. buffer the new results for delivery on the next cycle.
 *
 * Network/enroll failures bubble up so [CheckInWorker] applies WorkManager backoff; any
 * drained acks are restored to the buffer so they retry. Per-command failures never abort
 * the batch — they become `failed`/`unsupported`/`expired` results.
 */
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
    private val hardwareIdSource: HardwareIdSource = HardwareIdSource { null },
) {

    suspend fun runOnce() {
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

        val results = data.commands.map { dispatcher.dispatch(it) }
        pending.add(results)
        // Record each command outcome as a timeline event (flushed next cycle).
        results.forEach { eventSink.record(EventType.COMMAND_RESULT, "${it.commandId}:${it.status}") }
    }
}

/** A check-in was rejected by the server. Lets the worker retry with backoff. */
class CheckInException(message: String) : Exception(message)
