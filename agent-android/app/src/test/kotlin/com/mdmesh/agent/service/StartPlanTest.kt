package com.mdmesh.agent.service

import org.junit.Assert.assertEquals
import org.junit.Test

class StartPlanTest {

    @Test
    fun `a normal first start checks in and holds the socket for the grace window`() {
        assertEquals(
            StartPlan(grace = true, initialSync = true, loadCredentials = true),
            StartPlan.of(first = true, revived = false, hasCredentials = false),
        )
    }

    @Test
    fun `a service the heartbeat revives neither checks in nor holds the socket`() {
        // The heartbeat checks in itself (under its wakelock); a second check-in and a 10-minute hot socket on
        // battery, every time an OEM kills the service, is what this avoids.
        for (creds in listOf(true, false)) {
            assertEquals(
                StartPlan(grace = false, initialSync = false, loadCredentials = true),
                StartPlan.of(first = true, revived = true, hasCredentials = creds),
            )
        }
    }

    @Test
    fun `a repeat start without credentials retries the check-in, unless the heartbeat is doing it`() {
        assertEquals(
            StartPlan(grace = false, initialSync = true, loadCredentials = true),
            StartPlan.of(first = false, revived = false, hasCredentials = false),
        )
        assertEquals(
            StartPlan(grace = false, initialSync = false, loadCredentials = true),
            StartPlan.of(first = false, revived = true, hasCredentials = false),
        )
    }

    @Test
    fun `a repeat start of a running service does nothing`() {
        for (heartbeat in listOf(true, false)) {
            assertEquals(
                StartPlan(grace = false, initialSync = false, loadCredentials = false),
                StartPlan.of(first = false, revived = heartbeat, hasCredentials = true),
            )
        }
    }

    @Test
    fun `a sticky restart after Android killed the service counts as a revive`() {
        // No intent: the system restarting a START_STICKY service after the process died (an OEM or low-memory
        // kill). A boot or self-update starts it with an intent, from BootReceiver.
        assertEquals(true, StartPlan.revived(hasIntent = false, fromHeartbeat = false))
        assertEquals(true, StartPlan.revived(hasIntent = true, fromHeartbeat = true))
        assertEquals(false, StartPlan.revived(hasIntent = true, fromHeartbeat = false))
    }
}
