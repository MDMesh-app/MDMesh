package com.mdmesh.agent

import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfiguredKioskStarterTest {
    private val payload = KioskApplyPayload(mode = "single", pinPackage = "com.example")

    @Test
    fun `successful start enters saved payload then records and checks in`() = runBlocking {
        val calls = mutableListOf<String>()
        val starter = ConfiguredKioskStarter(
            enter = { received -> calls += "enter:${received.pinPackage}"; KioskResult.Ok },
            recordStart = { calls += "event" },
            requestCheckIn = { calls += "checkin" },
        )

        assertEquals(KioskResult.Ok, starter.start(payload))
        assertEquals(listOf("enter:com.example", "event", "checkin"), calls)
    }

    @Test
    fun `failed entry does not claim a local configured start or check in`() = runBlocking {
        val calls = mutableListOf<String>()
        val starter = ConfiguredKioskStarter(
            enter = { calls += "enter"; KioskResult.Failed("dpm") },
            recordStart = { calls += "event" },
            requestCheckIn = { calls += "checkin" },
        )

        assertEquals(KioskResult.Failed("dpm"), starter.start(payload))
        assertEquals(listOf("enter"), calls)
    }

    @Test
    fun `missing saved kiosk has no side effects`() = runBlocking {
        val calls = mutableListOf<String>()
        val starter = ConfiguredKioskStarter(
            enter = { calls += "enter"; KioskResult.Ok },
            recordStart = { calls += "event" },
            requestCheckIn = { calls += "checkin" },
        )

        assertNull(starter.start(null))
        assertEquals(emptyList<String>(), calls)
    }
}
