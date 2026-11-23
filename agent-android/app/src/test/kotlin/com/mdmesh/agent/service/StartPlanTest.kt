package com.mdmesh.agent.service

import com.mdmesh.agent.service.StartPlan.Starter
import org.junit.Assert.assertEquals
import org.junit.Test

class StartPlanTest {

    @Test
    fun `a normal first start checks in and holds the socket for the grace window`() {
        assertEquals(
            StartPlan(grace = true, initialSync = true, loadCredentials = true),
            StartPlan.of(first = true, starter = Starter.NORMAL, hasCredentials = false),
        )
    }

    @Test
    fun `a service the heartbeat revives neither checks in nor holds the socket`() {
        // The heartbeat checks in itself (under its wakelock); a second check-in and a 10-minute hot socket on
        // battery, every time an OEM kills the service, is what this avoids.
        for (creds in listOf(true, false)) {
            assertEquals(
                StartPlan(grace = false, initialSync = false, loadCredentials = true),
                StartPlan.of(first = true, starter = Starter.HEARTBEAT, hasCredentials = creds),
            )
        }
    }

    @Test
    fun `a sticky restart checks in at once but holds no grace socket`() {
        // After an OEM kill nothing else checks in right away (the heartbeat is up to 10 minutes off, the start-up
        // worker may wait for Doze), so the service does; only the hot socket is skipped.
        assertEquals(
            StartPlan(grace = false, initialSync = true, loadCredentials = true),
            StartPlan.of(first = true, starter = Starter.STICKY_RESTART, hasCredentials = true),
        )
    }

    @Test
    fun `a repeat start without credentials retries the check-in, unless the heartbeat is doing it`() {
        assertEquals(
            StartPlan(grace = false, initialSync = true, loadCredentials = true),
            StartPlan.of(first = false, starter = Starter.NORMAL, hasCredentials = false),
        )
        assertEquals(
            StartPlan(grace = false, initialSync = false, loadCredentials = true),
            StartPlan.of(first = false, starter = Starter.HEARTBEAT, hasCredentials = false),
        )
    }

    @Test
    fun `a repeat start of a running service does nothing`() {
        for (starter in Starter.entries) {
            assertEquals(
                StartPlan(grace = false, initialSync = false, loadCredentials = false),
                StartPlan.of(first = false, starter = starter, hasCredentials = true),
            )
        }
    }

    @Test
    fun `who started it`() {
        assertEquals(Starter.STICKY_RESTART, Starter.of(hasIntent = false, fromHeartbeat = false))
        assertEquals(Starter.HEARTBEAT, Starter.of(hasIntent = true, fromHeartbeat = true))
        assertEquals(Starter.NORMAL, Starter.of(hasIntent = true, fromHeartbeat = false))
    }
}
