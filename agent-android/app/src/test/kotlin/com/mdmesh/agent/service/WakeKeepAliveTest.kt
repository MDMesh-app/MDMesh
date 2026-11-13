package com.mdmesh.agent.service

import android.app.AlarmManager
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeKeepAliveTest {

    @Test
    fun `the heartbeat runs on the elapsed clock, so a wall-clock correction cannot delay it`() {
        var armed: Pair<Int, Long>? = null
        WakeKeepAlive.arm(now = 5_000L) { type, at -> armed = type to at }
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP to 5_000L + 10 * 60_000L, armed)
    }
}
