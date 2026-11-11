package com.mdmesh.agent.service

import android.app.AlarmManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeKeepAliveTest {

    private data class Armed(val type: Int, val triggerAt: Long, val allowWhileIdle: Boolean)

    private fun armAt(now: Long, sdk: Int = 35): Armed {
        var armed: Armed? = null
        WakeKeepAlive.arm(now, sdk) { type, at, idle -> armed = Armed(type, at, idle) }
        return armed!!
    }

    @Test
    fun `the heartbeat runs on the elapsed clock, so a wall-clock correction cannot delay it`() {
        val armed = armAt(now = 5_000L)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, armed.type)
        assertEquals(5_000L + 10 * 60_000L, armed.triggerAt)
    }

    @Test
    fun `the heartbeat still fires in Doze`() {
        assertTrue(armAt(now = 0L, sdk = 24).allowWhileIdle)
        assertTrue(armAt(now = 0L, sdk = 35).allowWhileIdle)
    }
}
