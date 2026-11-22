package com.mdmesh.core.sync

import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.ConfigApplyResult
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.EventType
import com.mdmesh.proto.TelemetryEventDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** The boot re-apply's failures reach the console as events instead of only logcat; every step still runs. */
class ReapplyStepsTest {
    private class Events : EventSink {
        val recorded = mutableListOf<Pair<String, String?>>()
        override fun record(type: String, detail: String?) { recorded += type to detail }
        override fun drain(max: Int): List<TelemetryEventDto> = emptyList()
        override fun restore(events: List<TelemetryEventDto>) = Unit
    }

    @Test
    fun `a step that throws or fails is recorded and the next still runs`() = runTest {
        val events = Events()
        var ran = 0
        val failures = ReapplySteps(events).run(
            "interrupted kiosk exit" to suspend { throw IOException("kiosk state could not be read: EIO") },
            "stale volume lock" to suspend { ran++; null },
            "config re-apply" to suspend { "kiosk failed: lock task refused" },
        )
        assertEquals(1, ran)
        val expected = listOf(
            "interrupted kiosk exit: kiosk state could not be read: EIO",
            "config re-apply: kiosk failed: lock task refused",
        )
        assertEquals(expected, failures)
        assertEquals(expected.map { EventType.CONFIG_REAPPLY_FAILED to it }, events.recorded)
    }

    @Test
    fun `failed outcomes are named, applied ones are not`() {
        val result = ConfigApplyResult(
            "r1",
            mapOf("policies.camera" to ConfigOutcome.APPLIED, "kiosk" to ConfigOutcome.failed("EIO")),
        )
        assertEquals("kiosk ${ConfigOutcome.failed("EIO")}", ReapplySteps.failedOutcomes(result))
        assertNull(ReapplySteps.failedOutcomes(ConfigApplyResult("r1", mapOf("kiosk" to ConfigOutcome.APPLIED))))
    }
}
