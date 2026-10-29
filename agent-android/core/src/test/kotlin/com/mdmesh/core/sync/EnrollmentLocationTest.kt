package com.mdmesh.core.sync

import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.install.InstallIntegrityPolicy
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.core.telemetry.TelemetryAssembler
import com.mdmesh.core.telemetry.TelemetrySource
import com.mdmesh.proto.DynamicState
import com.mdmesh.proto.TelemetryEventDto
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EnrollmentLocationTest {
    private fun coordinator(
        api: FakeMdmApi,
        telemetry: TelemetrySource,
    ): CheckInCoordinator {
        val identity = FakeIdentity(initialId = "dev-1", initialSecret = "sek-1")
        val events = object : EventSink {
            override fun record(type: String, detail: String?) = Unit
            override fun drain() = emptyList<TelemetryEventDto>()
            override fun restore(events: List<TelemetryEventDto>) = Unit
        }
        return CheckInCoordinator(
            api, EnrollmentManager(api, identity, FakeTokenProvider("t"), FakeCapabilitySource(), events),
            identity, FakeCapabilitySource(), CommandDispatcher(emptyList()), PendingResults(), { null }, telemetry,
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
}
