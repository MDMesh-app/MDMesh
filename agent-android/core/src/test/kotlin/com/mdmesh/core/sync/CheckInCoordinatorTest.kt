package com.mdmesh.core.sync

import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.install.InstallIntegrityPolicy
import com.mdmesh.proto.AgentCheckInResponse
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.CommandStatus
import com.mdmesh.core.net.ResponseEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CheckInCoordinatorTest {

    private class TestHandler : CommandHandler {
        override val type: String = "test.cmd"
        override suspend fun handle(command: CommandEnvelope): CommandResult =
            CommandResults.done(command)
    }

    /** Never returns, like an agent self-update: the process is replaced mid-command. */
    private class HangingHandler : CommandHandler {
        override val type: String = "test.hang"
        override suspend fun handle(command: CommandEnvelope): CommandResult = awaitCancellation()
    }

    /** Stands in for SharedPreferences: what a new process would read back. */
    private class FakeBackup : PendingResults.Backup {
        var raw: String? = null
        override fun read(): String? = raw
        override fun write(raw: String?) { this.raw = raw }
    }

    private fun coordinator(
        api: FakeMdmApi,
        pending: PendingResults,
        identityId: String = "dev-1",
        syncStatus: SyncStatus = SyncStatus(),
        installIntegrity: InstallIntegrityPolicy = InstallIntegrityPolicy(),
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
            dispatcher = CommandDispatcher(listOf(TestHandler(), HangingHandler())),
            pending = pending,
            stateSource = { null },
            telemetrySource = { null },
            eventSink = eventSink,
            syncStatus = syncStatus,
            installIntegrity = installIntegrity,
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
    fun `follows the server's installIntegrity on every check-in`() = runTest {
        val policy = InstallIntegrityPolicy()
        val api = FakeMdmApi().apply {
            checkInResponse = ResponseEnvelope(
                status = "OK",
                data = AgentCheckInResponse(installIntegrity = AgentCheckInResponse.INSTALL_INTEGRITY_REQUIRED),
            )
        }
        val coordinator = coordinator(api, PendingResults(), installIntegrity = policy)
        coordinator.runOnce()
        assertTrue(policy.required)

        // A server that doesn't send it (an older one, e.g. after a rollback) gets the old behaviour back.
        api.checkInResponse = ResponseEnvelope(status = "OK", data = AgentCheckInResponse())
        coordinator.runOnce()
        assertFalse(policy.required)
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

    @Test
    fun `concurrent runOnce calls are serialized, never interleaved`() = runTest {
        val api = FakeMdmApi().apply { checkInGate = CompletableDeferred() }
        val coordinator = coordinator(api, PendingResults())

        val first = launch { coordinator.runOnce() }
        val second = launch { coordinator.runOnce() }
        testScheduler.advanceUntilIdle()

        // With one cycle held in flight, the second caller must be parked on the mutex.
        assertEquals("second cycle must wait for the in-flight one", 1, api.checkInRequests.size)
        api.checkInGate!!.complete(Unit)
        joinAll(first, second)
        assertEquals(2, api.checkInRequests.size)
    }

    @Test
    fun `records the failure in SyncStatus and clears it on the next success`() = runTest {
        val api = FakeMdmApi().apply { checkInThrows = IOException("network down") }
        val syncStatus = SyncStatus()
        val coordinator = coordinator(api, PendingResults(), syncStatus = syncStatus)

        runCatching { coordinator.runOnce() }
        assertEquals("network down", syncStatus.lastError.value?.message)
        assertNotNull(syncStatus.lastError.value?.atMillis)

        api.checkInThrows = null
        coordinator.runOnce()
        assertNull(syncStatus.lastError.value)
    }

    @Test
    fun `remembers when a check-in last reached the server`() = runTest {
        val api = FakeMdmApi().apply { checkInThrows = IOException("network down") }
        val last = InMemoryLastCheckIn()
        val coordinator = coordinator(api, PendingResults(), syncStatus = SyncStatus(last))
        runCatching { coordinator.runOnce() }
        assertNull("a failed check-in is not recorded", last.okAtMs)
        api.checkInThrows = null
        coordinator.runOnce()
        assertNotNull(last.okAtMs)
    }

    @Test
    fun `a rejected check-in is not recorded as reaching the server`() = runTest {
        val last = InMemoryLastCheckIn()
        val api = FakeMdmApi().apply { checkInResponse = ResponseEnvelope(status = "ERROR", message = "nope") }
        val coordinator = coordinator(api, PendingResults(), syncStatus = SyncStatus(last))
        runCatching { coordinator.runOnce() }
        assertNull("ERROR envelope", last.okAtMs)
        api.checkInResponse = ResponseEnvelope(status = "OK", data = null)
        runCatching { coordinator.runOnce() }
        assertNull("OK without data", last.okAtMs)
    }

    @Test
    fun `the check-in counts as reaching the server before its commands run`() = runTest {
        // A long command (an app.install download) must not look like a missed check-in.
        val api = FakeMdmApi().apply {
            checkInResponse = ResponseEnvelope(
                status = "OK",
                data = AgentCheckInResponse(commands = listOf(command("c1").copy(type = "test.hang"))),
            )
        }
        val last = InMemoryLastCheckIn()
        val cycle = launch { coordinator(api, PendingResults(), syncStatus = SyncStatus(last)).runOnce() }
        testScheduler.advanceUntilIdle()
        assertNotNull("recorded while the command is still running", last.okAtMs)
        cycle.cancel()
    }

    @Test
    fun `delivered acks leave the persisted buffer only after the check-in succeeds`() = runTest {
        val backup = FakeBackup()
        val old = CommandResult(commandId = "old", status = CommandStatus.DONE, completedAt = "t")
        PendingResults(backup).add(listOf(old))
        val api = FakeMdmApi().apply { checkInThrows = IOException("network down") }

        runCatching { coordinator(api, PendingResults(backup)).runOnce() }
        assertEquals("kept after a failed check-in", listOf("old"), PendingResults(backup).drain().map { it.commandId })

        api.checkInThrows = null
        coordinator(api, PendingResults(backup)).runOnce()
        assertEquals("old", api.checkInRequests.last().results.single().commandId)
        assertTrue("gone once delivered", PendingResults(backup).drain().isEmpty())
    }

    @Test
    fun `each result is persisted as soon as its command finishes`() = runTest {
        val backup = FakeBackup()
        val api = FakeMdmApi().apply {
            checkInResponse = ResponseEnvelope(
                status = "OK",
                data = AgentCheckInResponse(
                    commands = listOf(command("c1"), command("c2").copy(type = "test.hang")),
                ),
            )
        }

        val cycle = launch { coordinator(api, PendingResults(backup)).runOnce() }
        testScheduler.advanceUntilIdle()

        // c2 is still running (a self-update replaces the process here): c1's result must already be saved.
        assertEquals(listOf("c1"), PendingResults(backup).drain().map { it.commandId })
        cycle.cancel()
    }

    private fun ack(id: String) = CommandResult(commandId = id, status = CommandStatus.DONE, completedAt = "t")

    @Test
    fun `a backlog beyond one batch waits for the next check-in`() = runTest {
        val api = FakeMdmApi()
        val total = PendingResults.MAX_BATCH_RESULTS + 5
        val pending = PendingResults().apply { add((1..total).map { ack("c$it") }) }

        val coordinator = coordinator(api, pending)
        coordinator.runOnce()
        assertEquals(listOf(PendingResults.MAX_BATCH_RESULTS), api.checkInRequests.map { it.results.size })

        coordinator.runOnce()
        assertEquals(5, api.checkInRequests.last().results.size)
    }

    @Test
    fun `results the server did not accept are resent on the next check-in`() = runTest {
        val api = FakeMdmApi().apply {
            checkInResponse = ResponseEnvelope(status = "OK", data = AgentCheckInResponse(resultsAccepted = 1))
        }
        val pending = PendingResults().apply { add(listOf(ack("a"), ack("b"), ack("c"))) }

        val coordinator = coordinator(api, pending)
        coordinator.runOnce()
        coordinator.runOnce()

        assertEquals(listOf(listOf("a", "b", "c"), listOf("b", "c")),
            api.checkInRequests.map { r -> r.results.map { it.commandId } })
        assertEquals(listOf("c"), pending.drain().map { it.commandId })
    }
}
