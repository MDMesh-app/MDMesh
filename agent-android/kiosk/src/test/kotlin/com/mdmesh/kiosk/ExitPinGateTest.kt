package com.mdmesh.kiosk

import com.mdmesh.kiosk.ExitPinGate.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ExitPinGate] on the JVM: an [InMemoryPinAttemptStore] and a manually advanced uptime clock. */
class ExitPinGateTest {

    private val pin = "48213907"
    private var uptimeMs = 5 * 60 * 60_000L // 5 h since boot
    private val store = InMemoryPinAttemptStore()
    private val gate = ExitPinGate(store) { uptimeMs }

    private fun wrong() = gate.attempt("00000000", pin)

    /** Wrong PINs until the exit locks; returns that lockout. */
    private fun lockOut(): Outcome.Locked {
        repeat(ExitPinGate.FREE_ATTEMPTS - 1) { wrong() }
        return wrong() as Outcome.Locked
    }

    @Test
    fun `no PIN means no on-device exit`() {
        assertFalse(gate.isAvailable(null))
        assertFalse(gate.isAvailable(""))
        assertFalse(gate.isAvailable("   "))
        assertEquals(Outcome.Unavailable, gate.attempt("", null))
        assertEquals(Outcome.Unavailable, gate.attempt("", " "))
        assertTrue(gate.isAvailable(pin))
    }

    @Test
    fun `the right PIN opens the exit and leaves no count behind`() {
        assertEquals(Outcome.Open, gate.attempt(pin, pin))
        assertEquals(0, store.failures)
    }

    @Test
    fun `a PIN must match exactly`() {
        assertEquals(Outcome.Wrong(2), gate.attempt(pin.dropLast(1), pin))
        assertEquals(Outcome.Wrong(1), gate.attempt("$pin ", pin))
    }

    @Test
    fun `full-width digits match the PIN`() {
        assertEquals(Outcome.Open, gate.attempt("４８２１３９０７", pin))
    }

    @Test
    fun `wrong PINs count down, then lock the exit`() {
        assertEquals(Outcome.Wrong(2), wrong())
        assertEquals(Outcome.Wrong(1), wrong())
        val locked = wrong() as Outcome.Locked
        assertTrue("the PIN that starts a lockout is reported", locked.started)
        assertEquals(30_000L, locked.remainingMs)
        assertEquals(3, locked.failures)
    }

    @Test
    fun `while locked even the right PIN is not checked`() {
        lockOut()
        uptimeMs += 29_000L
        val o = gate.attempt(pin, pin) as Outcome.Locked
        assertFalse("reported once, not on every attempt", o.started)
        assertEquals(1_000L, o.remainingMs)
        assertEquals(3, store.failures)
    }

    @Test
    fun `each wrong PIN after a lockout locks for longer, and the last step repeats`() {
        val steps = mutableListOf(lockOut().remainingMs)
        repeat(ExitPinGate.LOCKOUTS_MS.size + 1) {
            uptimeMs += steps.last()
            steps += (wrong() as Outcome.Locked).remainingMs
        }
        assertEquals(ExitPinGate.LOCKOUTS_MS + ExitPinGate.LOCKOUTS_MS.last() + ExitPinGate.LOCKOUTS_MS.last(), steps)
    }

    @Test
    fun `the right PIN after a lockout opens the exit and clears the count`() {
        lockOut()
        uptimeMs += 30_000L
        assertNull(gate.remainingLockMs())
        assertEquals(Outcome.Open, gate.attempt(pin, pin))
        assertEquals(0, store.failures)
        assertEquals(Outcome.Wrong(2), wrong())
    }

    @Test
    fun `a new process keeps the lockout`() {
        lockOut()
        uptimeMs += 10_000L
        assertEquals(20_000L, ExitPinGate(store) { uptimeMs }.remainingLockMs())
    }

    @Test
    fun `a reboot restarts the lockout instead of ending it`() {
        lockOut()
        uptimeMs = 2_000L // just booted: the uptime clock started again from zero
        val afterReboot = ExitPinGate(store) { uptimeMs }
        assertEquals(30_000L, afterReboot.remainingLockMs())
        uptimeMs += 29_000L
        assertTrue(afterReboot.attempt(pin, pin) is Outcome.Locked)
        uptimeMs += 1_000L
        assertEquals(Outcome.Open, afterReboot.attempt(pin, pin))
    }

    @Test
    fun `after a reboot the lockout never ends before its full time has really passed`() {
        // Locked at 10 min of uptime for 30 s; after a reboot uptime restarts at 0, so any reading past the old lock
        // time means at least that much real time has passed since boot alone.
        uptimeMs = 10 * 60_000L
        lockOut()
        uptimeMs = 10 * 60_000L + 5_000L // rebooted and has been up a little longer than before
        assertEquals("treated as the same boot: 25 s left", 25_000L, gate.remainingLockMs())
        uptimeMs += 25_000L
        assertNull(gate.remainingLockMs())
    }

    @Test
    fun `an attempt that can't be saved is refused, not checked`() {
        store.writable = false
        assertEquals("not even the right PIN", Outcome.Unavailable, gate.attempt(pin, pin))
        assertEquals(Outcome.Unavailable, wrong())
        assertEquals(0, store.failures)
    }

    @Test
    fun `an attempt is counted before the PIN is compared`() {
        // A store that saves the count but then fails: the wrong PIN stays counted.
        var saves = 0
        val oneSave = object : PinAttemptStore by store {
            override fun write(failures: Int, lastFailureAt: Long): Boolean =
                ++saves == 1 && store.write(failures, lastFailureAt)
        }
        assertEquals(Outcome.Open, ExitPinGate(oneSave) { uptimeMs }.attempt(pin, pin))
        assertEquals("the reset after a right PIN failed, so the attempt stays counted", 1, store.failures)
    }

    @Test
    fun `describe rounds up`() {
        assertEquals("30 s", ExitPinGate.describe(30_000L))
        assertEquals("1 s", ExitPinGate.describe(1L))
        assertEquals("1 min", ExitPinGate.describe(59_500L))
        assertEquals("1 min", ExitPinGate.describe(60_000L))
        assertEquals("2 min", ExitPinGate.describe(60_001L))
        assertEquals("15 min", ExitPinGate.describe(15 * 60_000L))
        assertEquals("1 h", ExitPinGate.describe(60 * 60_000L))
    }
}
