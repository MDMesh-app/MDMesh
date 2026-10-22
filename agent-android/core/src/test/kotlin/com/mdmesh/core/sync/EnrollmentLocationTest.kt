package com.mdmesh.core.sync

import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.install.InstallIntegrityPolicy
import com.mdmesh.core.net.ResponseEnvelope
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.core.telemetry.TelemetrySource
import com.mdmesh.core.telemetry.TelemetryAssembler
import com.mdmesh.proto.DynamicState
import com.mdmesh.proto.AgentCheckInResponse
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.TelemetryEventDto
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EnrollmentLocationTest {
    private fun coordinator(
        api: FakeMdmApi,
        telemetry: TelemetrySource,
        applyGps: () -> Unit = {},
    ): CheckInCoordinator {
        val identity = FakeIdentity(initialId = "dev-1", initialSecret = "sek-1")
        val events = object : EventSink {
            override fun record(type: String, detail: String?) = Unit
            override fun drain() = emptyList<TelemetryEventDto>()
            override fun restore(events: List<TelemetryEventDto>) = Unit
        }
        val handler = object : CommandHandler {
            override val type = "config.apply"
            override suspend fun handle(command: CommandEnvelope): CommandResult {
                applyGps()
                return CommandResults.done(command)
            }
        }
        return CheckInCoordinator(
            api, EnrollmentManager(api, identity, FakeTokenProvider("t"), FakeCapabilitySource(), events),
            identity, FakeCapabilitySource(), CommandDispatcher(listOf(handler)), PendingResults(), { null }, telemetry,
            events, InstallIntegrityPolicy(),
        )
    }

    @Test
    fun `enrolment telemetry frees the caller thread to deliver location callbacks`() {
        val executor = Executors.newSingleThreadExecutor()
        executor.asCoroutineDispatcher().use { main ->
            val callback = CountDownLatch(1)
            var completed = false
            runBlocking(main) {
                val source = TelemetryAssembler(
                    hardware = { error("no hardware") }, identity = { error("no identity") },
                    security = { error("no security") }, dynamic = {
                        executor.execute { callback.countDown() }
                        completed = callback.await(2, TimeUnit.SECONDS)
                        DynamicState(
                            batteryPct = 50, batteryHealth = "good", batteryTempC = 25.0, batteryVoltageMv = 4000,
                            chargingSource = "none", freeStorageBytes = 1, freeRamBytes = 1, networkType = "wifi",
                            screenOn = true, locked = false, kioskActive = false, uptimeMs = 1, lastBootAt = 1,
                        )
                    },
                )
                coordinator(FakeMdmApi(), source).runOnce()
            }
            assertTrue("location callback must run while telemetry awaits it", completed)
        }
    }

    @Test
    fun `GPS config produces an immediate active report without a manual location command`() = runTest {
        val gps = buildJsonObject { put("location", buildJsonObject { put("mode", "active") }) }
        val api = FakeMdmApi().apply {
            checkInResponse = ResponseEnvelope(status = "OK", data = AgentCheckInResponse(commands = listOf(
                CommandEnvelope(
                    commandId = "gps", issuedAt = "2026-01-01T00:00:00Z", type = "config.apply", payload = gps,
                ),
            )))
        }
        var mode = "passive"
        val collected = mutableListOf<String>()
        coordinator(api, TelemetrySource { collected.add(mode); null }, { mode = "active" }).runOnce()
        assertEquals(2, api.checkInRequests.size)
        assertEquals(listOf("passive", "active"), collected)
        assertEquals("gps", api.checkInRequests[1].results.single().commandId)
    }
    @Test
    fun `passive and off configs do not start a GPS follow-up`() = runTest {
        for (mode in listOf("passive", "off")) {
            val payload = buildJsonObject { put("location", buildJsonObject { put("mode", mode) }) }
            val api = FakeMdmApi().apply {
                checkInResponse = ResponseEnvelope(status = "OK", data = AgentCheckInResponse(commands = listOf(
                    CommandEnvelope(
                        commandId = mode, issuedAt = "2026-01-01T00:00:00Z", type = "config.apply", payload = payload,
                    ),
                )))
            }
            coordinator(api, TelemetrySource { null }).runOnce()
            assertEquals(1, api.checkInRequests.size)
        }
    }

}
