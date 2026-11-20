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
    private var checksAsked = 0

    private fun newProcess() =
        FactoryResetWipeHold(store, { boot }, { nowMs }, onHold = { checksAsked++ }) { expired++ }

    /** WipeHoldExpiryWorker acts on its own only when its read didn't end the hold (onExpired did). */
    @Test
    fun `only the read that ends an expired hold says so`() {
        val hold = newProcess()
        hold.hold()
        assertFalse(hold.read().endedNow)

        nowMs += FactoryResetWipeHold.MAX_HOLD_MS

        assertEquals(FactoryResetWipeHold.Reading(null, endedNow = true), hold.read())
        assertEquals(FactoryResetWipeHold.Reading(null, endedNow = false), hold.read())
        assertEquals(1, expired)
    }

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
    fun `a boot count that can't be read once keeps the saved hold`() {
        newProcess().hold()
        boot = 0L

        assertFalse(newProcess().held)
        boot = 7L

        assertTrue(newProcess().held)
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

    @Test
    fun `a hold asks for its deadline check, which ends it with no other read`() {
        newProcess().hold()
        assertEquals(1, checksAsked)

        nowMs += FactoryResetWipeHold.MAX_HOLD_MS
        // The scheduled check, in whatever process is alive by then.
        assertNull(newProcess().remainingMs)

        assertEquals(1, expired)
        assertNull(store.saved)
    }

    @Test
    fun `a check that comes early reports the time left and keeps the hold`() {
        val hold = newProcess()
        hold.hold()
        nowMs += 4_000L

        assertEquals(FactoryResetWipeHold.MAX_HOLD_MS - 4_000L, hold.remainingMs)
        assertTrue(hold.held)
        assertEquals(0, expired)
    }

    @Test
    fun `the check and a lazy read at the deadline report the expiry once`() {
        val hold = newProcess()
        hold.hold()
        nowMs += FactoryResetWipeHold.MAX_HOLD_MS

        assertNull(hold.remainingMs)
        assertFalse(hold.held)
        assertNull(newProcess().remainingMs)
        assertEquals(1, expired)
    }

    @Test
    fun `uptime behind the saved hold is a reboot the boot count missed, not a hold to keep`() {
        nowMs = 5_000_000L
        newProcess().hold()
        nowMs = 2_000L // same boot count, but uptime restarted

        assertFalse(newProcess().held)
        assertNull(store.saved)
        assertEquals("a reboot is not an expiry", 0, expired)
    }

    @Test
    fun `an expiry callback that throws doesn't escape the read, and the hold still ends once`() {
        var calls = 0
        val hold = FactoryResetWipeHold(store, { boot }, { nowMs }) {
            calls++
            error("event log unwritable")
        }
        hold.hold()
        nowMs += FactoryResetWipeHold.MAX_HOLD_MS

        assertFalse(hold.held)
        assertFalse(hold.held)
        assertEquals(1, calls)
        assertNull(store.saved)
    }

    @Test
    fun `expireIfDue ends a hold past its deadline, for callers that need nothing else`() {
        newProcess().hold()
        nowMs += FactoryResetWipeHold.MAX_HOLD_MS

        newProcess().expireIfDue()

        assertEquals(1, expired)
        assertNull(store.saved)
    }
}
