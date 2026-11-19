package com.mdmesh.policy.factoryreset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold outlives a process restart within the boot it was set in, and no further; and it ends on
 * its own when no reboot came within [FactoryResetWipeHold.MAX_HOLD_MS] (#222).
 */
class FactoryResetWipeHoldTest {

    /** Survives "process restarts": each new hold instance reads the same store. */
    private val store = InMemoryWipeHoldStore()
    private var boot = 7L
    private var nowMs = 1_000L
    private var expired = 0

    private fun newProcess() = FactoryResetWipeHold(store, { boot }, { nowMs }) { expired++ }

    @Test
    fun `a hold survives a process restart in the same boot`() {
        newProcess().hold()

        assertEquals(SavedWipeHold(boot = 7L, atMs = 1_000L), store.saved)
        assertTrue(newProcess().held)
    }

    @Test
    fun `a hold from an older boot is ignored and cleared`() {
        newProcess().hold()
        boot = 8L

        assertFalse(newProcess().held)
        assertNull(store.saved)
        assertEquals("a reboot is the wipe (or its failure), not an expiry", 0, expired)
    }

    @Test
    fun `release clears the saved hold`() {
        val hold = newProcess()
        hold.hold()
        hold.release()

        assertFalse(hold.held)
        assertFalse(newProcess().held)
    }

    @Test
    fun `without a boot count the hold lasts only for this process`() {
        boot = 0L
        val hold = newProcess()
        hold.hold()

        assertTrue(hold.held)
        assertNull(store.saved)
        assertFalse(newProcess().held)
    }

    @Test
    fun `still holds just within the bound`() {
        val hold = newProcess()
        hold.hold()
        nowMs += FactoryResetWipeHold.MAX_HOLD_MS - 1

        assertTrue(hold.held)
        assertTrue(newProcess().held)
        assertEquals(0, expired)
    }

    @Test
    fun `ends once the bound passes without a reboot, and reports it once`() {
        val hold = newProcess()
        hold.hold()
        nowMs += FactoryResetWipeHold.MAX_HOLD_MS

        assertFalse(hold.held)
        assertFalse(hold.held)
        assertNull(store.saved)
        assertEquals(1, expired)
    }

    @Test
    fun `a saved hold past the bound ends in a restarted process too`() {
        newProcess().hold()
        nowMs += FactoryResetWipeHold.MAX_HOLD_MS + 1

        assertFalse(newProcess().held)
        assertNull(store.saved)
        assertEquals(1, expired)
    }
}
