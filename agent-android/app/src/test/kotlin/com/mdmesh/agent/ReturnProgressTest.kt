package com.mdmesh.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Return to kiosk's progress and its failure message, which shows until the next refresh. */
class ReturnProgressTest {

    @Test fun `a failure shows after the return ends`() {
        val failed = ReturnProgress().started().finished("offline")
        assertFalse(failed.running)
        assertEquals("offline", failed.failure)
    }

    @Test fun `starting again clears the old failure`() {
        val again = ReturnProgress().started().finished("offline").started()
        assertTrue(again.running)
        assertNull(again.failure)
    }

    @Test fun `the failure clears when the device's state changes`() {
        assertNull(ReturnProgress().started().finished("offline").refreshed(factsChanged = true).failure)
    }

    @Test fun `a refresh with nothing changed (a rotation) keeps it`() {
        assertEquals("offline", ReturnProgress().started().finished("offline").refreshed(factsChanged = false).failure)
    }

    @Test fun `leaving the screen clears it`() {
        assertNull(ReturnProgress().started().finished("offline").left().failure)
    }

    @Test fun `a refresh never stops a running return`() {
        val running = ReturnProgress().started()
        assertTrue(running.refreshed(factsChanged = true).running)
        assertTrue(running.left().running)
    }
}
