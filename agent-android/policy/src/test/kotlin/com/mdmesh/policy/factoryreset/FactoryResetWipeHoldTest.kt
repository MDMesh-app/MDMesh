package com.mdmesh.policy.factoryreset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hold outlives a process restart within the boot it was set in, and no further (#222). */
class FactoryResetWipeHoldTest {

    /** Survives "process restarts": each new hold instance reads the same store. */
    private val store = InMemoryWipeHoldStore()
    private var boot = 7L

    private fun newProcess() = FactoryResetWipeHold(store) { boot }

    @Test
    fun `a hold survives a process restart in the same boot`() {
        newProcess().hold()

        assertEquals(7L, store.heldBoot)
        assertTrue(newProcess().held)
    }

    @Test
    fun `a hold from an older boot is ignored and cleared`() {
        newProcess().hold()
        boot = 8L

        assertFalse(newProcess().held)
        assertNull(store.heldBoot)
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
        assertNull(store.heldBoot)
        assertFalse(newProcess().held)
    }
}
