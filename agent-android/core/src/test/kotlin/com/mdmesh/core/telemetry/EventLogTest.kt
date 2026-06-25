package com.mdmesh.core.telemetry

import com.mdmesh.proto.TelemetryEventDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLogTest {
    @Test fun encodeDecodeRoundTrip() {
        val list = listOf(TelemetryEventDto("boot", 1), TelemetryEventDto("appInstalled", 2, "com.x"))
        assertEquals(list, EventLog.decode(EventLog.encode(list)))
    }

    @Test fun capKeepsMostRecent() {
        val list = (1..600).map { TelemetryEventDto("e", it.toLong()) }
        val capped = EventLog.cap(list)
        assertEquals(500, capped.size)
        assertEquals(600L, capped.last().ts) // newest kept
        assertEquals(101L, capped.first().ts) // oldest 100 dropped
    }

    @Test fun decodeNullAndGarbageYieldEmpty() {
        assertTrue(EventLog.decode(null).isEmpty())
        assertTrue(EventLog.decode("not json").isEmpty())
    }
}
