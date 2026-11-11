package com.mdmesh.agent.service

import org.junit.Assert.assertEquals
import org.junit.Test

class StartPlanTest {

    @Test
    fun `a normal first start checks in and holds the socket for the grace window`() {
        assertEquals(
            StartPlan(grace = true, initialSync = true, loadCredentials = true),
            StartPlan.of(first = true, fromHeartbeat = false, hasCredentials = false),
        )
    }

    @Test
    fun `a service the heartbeat revives neither checks in nor holds the socket`() {
        // The heartbeat checks in itself (under its wakelock); a second check-in and a 10-minute hot socket on
        // battery, every time an OEM kills the service, is what this avoids.
        for (creds in listOf(true, false)) {
            assertEquals(
                StartPlan(grace = false, initialSync = false, loadCredentials = true),
                StartPlan.of(first = true, fromHeartbeat = true, hasCredentials = creds),
            )
        }
    }

    @Test
    fun `a repeat start without credentials retries the check-in, unless the heartbeat is doing it`() {
        assertEquals(
            StartPlan(grace = false, initialSync = true, loadCredentials = true),
            StartPlan.of(first = false, fromHeartbeat = false, hasCredentials = false),
        )
        assertEquals(
            StartPlan(grace = false, initialSync = false, loadCredentials = true),
            StartPlan.of(first = false, fromHeartbeat = true, hasCredentials = false),
        )
    }

    @Test
    fun `a repeat start of a running service does nothing`() {
        for (heartbeat in listOf(true, false)) {
            assertEquals(
                StartPlan(grace = false, initialSync = false, loadCredentials = false),
                StartPlan.of(first = false, fromHeartbeat = heartbeat, hasCredentials = true),
            )
        }
    }
}
