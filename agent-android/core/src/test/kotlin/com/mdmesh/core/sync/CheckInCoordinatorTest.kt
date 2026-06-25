package com.mdmesh.core.sync

import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.proto.AgentCheckInResponse
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.CommandStatus
import com.mdmesh.core.net.ResponseEnvelope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CheckInCoordinatorTest {

    private class TestHandler : CommandHandler {
        override val type: String = "test.cmd"
        override suspend fun handle(command: CommandEnvelope): CommandResult =
            CommandResults.done(command)
    }

    private fun coordinator(
        api: FakeMdmApi,
        pending: PendingResults,
        identityId: String = "dev-1",
    ): CheckInCoordinator {
        val identity = FakeIdentity(initialId = identityId, initialSecret = "sek-1")
        val eventSink = object : com.mdmesh.core.telemetry.EventSink {
            override fun record(type: String, detail: String?) {}
            override fun drain() = emptyList<com.mdmesh.proto.TelemetryEventDto>()
            override fun restore(events: List<com.mdmesh.proto.TelemetryEventDto>) {}
        }
        val enrollment = EnrollmentManager(api, identity, FakeTokenProvider("t"), FakeCapabilitySource(), eventSink)
        return CheckInCoordinator(
            api = api,
            enrollment = enrollment,
            identity = identity,
            capabilitySource = FakeCapabilitySource(),
            dispatcher = CommandDispatcher(listOf(TestHandler())),
            pending = pending,
            stateSource = { null },
            telemetrySource = { null },
            eventSink = eventSink,
        )
    }

    private fun command(id: String) =
        CommandEnvelope(commandId = id, issuedAt = "2026-01-01T00:00:00Z", type = "test.cmd")

    @Test
    fun `dispatches returned commands and buffers their results`() = runTest {
        val api = FakeMdmApi().apply {
            checkInResponse = ResponseEnvelope(
                status = "OK",
                data = AgentCheckInResponse(commands = listOf(command("c1"))),
            )
        }
        val pending = PendingResults()

        coordinator(api, pending).runOnce()

        assertEquals(1, api.checkInRequests.size)
        assertEquals("dev-1", api.checkInRequests.first().deviceId)
        assertEquals("must present the per-device secret as a bearer token", "Bearer sek-1", api.checkInAuth.first())
        assertTrue("first cycle sends no acks", api.checkInRequests.first().results.isEmpty())
        // The dispatched command's result is buffered for the next cycle.
        val buffered = pending.drain()
        assertEquals(1, buffered.size)
        assertEquals("c1", buffered.first().commandId)
        assertEquals(CommandStatus.DONE, buffered.first().status)
    }

    @Test
    fun `delivers previously-buffered acks on the next check-in`() = runTest {
        val api = FakeMdmApi()
        val pending = PendingResults().apply {
            add(listOf(CommandResult(commandId = "old", status = CommandStatus.DONE, completedAt = "t")))
        }

        coordinator(api, pending).runOnce()

        val sent = api.checkInRequests.first().results
        assertEquals(1, sent.size)
        assertEquals("old", sent.first().commandId)
        assertTrue("buffer emptied after successful delivery", pending.drain().isEmpty())
    }

    @Test
    fun `restores acks when the check-in call fails`() = runTest {
        val api = FakeMdmApi().apply { checkInThrows = IOException("network down") }
        val pending = PendingResults().apply {
            add(listOf(CommandResult(commandId = "old", status = CommandStatus.DONE, completedAt = "t")))
        }

        runCatching { coordinator(api, pending).runOnce() }

        // Acks must survive a failed delivery so they retry next cycle.
        assertEquals(1, pending.drain().size)
    }
}
