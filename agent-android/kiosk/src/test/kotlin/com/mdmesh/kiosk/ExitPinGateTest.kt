package com.mdmesh.kiosk

import com.mdmesh.kiosk.ExitPinGate.Companion.FIRST_ROUND_TRIES
import com.mdmesh.kiosk.ExitPinGate.Companion.LOCKOUTS_MS
import com.mdmesh.kiosk.ExitPinGate.Companion.ROUND_TRIES
import com.mdmesh.kiosk.ExitPinGate.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** [ExitPinGate] on the JVM: an [InMemoryPinAttemptStore] and a manually advanced uptime clock. */
class ExitPinGateTest {

    private val pin = "482139"
    private var uptimeMs = 5 * 60 * 60_000L // 5 h since boot
    private val store = InMemoryPinAttemptStore()
    private val gate = ExitPinGate(store) { uptimeMs }

    private fun wrong() = gate.attempt("000000", pin)

    /** Wrong PINs until the exit locks (the rest of the current round); returns that lockout. */
    private fun lockOut(): Outcome.Locked {
        while (true) {
            val o = wrong()
            if (o is Outcome.Locked) return o
            check(o is Outcome.Wrong) { "unexpected $o" }
        }
    }

    // --- the curve ---------------------------------------------------------------------------------------------------

    @Test
    fun `the first round has room for typos, then the exit locks for a minute`() {
        for (left in FIRST_ROUND_TRIES - 1 downTo 1) assertEquals(Outcome.Wrong(left), wrong())
        val locked = wrong() as Outcome.Locked
        assertTrue("the PIN that starts a lockout is reported", locked.started)
        assertEquals(60_000L, locked.remainingMs)
        assertEquals(FIRST_ROUND_TRIES, locked.failures)
    }

    @Test
    fun `each lockout grants a round of tries, and each round locks for longer`() {
        val steps = mutableListOf(lockOut().remainingMs)
        repeat(LOCKOUTS_MS.size - 1) {
            uptimeMs += steps.last()
            // A full round after every lockout: a fat-fingered PIN after waiting doesn't lock it again at once.
            for (left in ROUND_TRIES - 1 downTo 1) assertEquals(Outcome.Wrong(left), wrong())
            steps += (wrong() as Outcome.Locked).remainingMs
        }
        assertEquals(LOCKOUTS_MS, steps)
    }

    @Test
    fun `once the hour is reached, every further wrong PIN locks it for another hour`() {
        repeat(LOCKOUTS_MS.size) {
            val o = lockOut()
            uptimeMs += o.remainingMs
        }
        assertEquals("one try, then locked", 1, gate.attemptsLeft(pin))
        repeat(10) {
            val o = wrong() as Outcome.Locked
            assertTrue("each wrong PIN starts a lockout", o.started)
            assertEquals(LOCKOUTS_MS.last(), o.remainingMs)
            uptimeMs += LOCKOUTS_MS.last() - 1
            assertTrue("not a second earlier", gate.attempt(pin, pin) is Outcome.Locked)
            uptimeMs += 1
        }
        assertEquals(Outcome.Open, gate.attempt(pin, pin))
    }

    @Test
    fun `an hour's lockout takes many wrong PINs and twenty minutes of waiting`() {
        var wrongPins = 0
        var waited = 0L
        while (true) {
            val o = wrong()
            wrongPins++
            if (o is Outcome.Locked) {
                if (o.remainingMs == LOCKOUTS_MS.last()) break
                waited += o.remainingMs
                uptimeMs += o.remainingMs
            }
        }
        assertEquals(FIRST_ROUND_TRIES + ROUND_TRIES * (LOCKOUTS_MS.size - 1), wrongPins)
        assertEquals(21 * 60_000L, waited)
    }

    @Test
    fun `brute force is held to one guess an hour`() {
        // A day of guessing as fast as the gate allows.
        val day = 24 * 60 * 60_000L
        val start = uptimeMs
        var guesses = 0
        while (uptimeMs - start < day) {
            when (val o = wrong()) {
                is Outcome.Wrong -> guesses++
                is Outcome.Locked -> {
                    if (o.started) guesses++
                    uptimeMs += o.remainingMs
                }
                else -> error("unexpected $o")
            }
        }
        // The rounds up to the hour (14 guesses in 21 min), then one an hour: a 6-digit PIN takes decades on average.
        val toTheHour = FIRST_ROUND_TRIES + ROUND_TRIES * (LOCKOUTS_MS.size - 1)
        assertTrue("guesses in a day: $guesses", guesses <= toTheHour + 24)
    }

    @Test
    fun `a legacy 4-digit PIN is guessed no faster than one an hour after the rounds`() {
        val legacy = "4931"
        val start = uptimeMs
        var guess = 0
        var opened = false
        while (!opened) {
            when (val o = gate.attempt("%04d".format(guess), legacy)) {
                Outcome.Open -> opened = true
                is Outcome.Wrong -> guess++
                is Outcome.Locked -> {
                    if (o.started) guess++
                    uptimeMs += o.remainingMs
                }
                else -> error("unexpected $o")
            }
        }
        val hours = (uptimeMs - start) / (60 * 60_000L)
        // 4,931 wrong guesses: 14 in the rounds, then one an hour.
        assertEquals(4931 - (FIRST_ROUND_TRIES + ROUND_TRIES * (LOCKOUTS_MS.size - 1)) + 1L, hours)
    }

    @Test
    fun `attemptsLeft is what the last wrong PIN said, read back from the store`() {
        assertNull(gate.attemptsLeft(pin))
        wrong()
        assertEquals(FIRST_ROUND_TRIES - 1, gate.attemptsLeft(pin))
        // A new gate on the same store (process death, rotation) reads the same.
        assertEquals(FIRST_ROUND_TRIES - 1, ExitPinGate(store) { uptimeMs }.attemptsLeft(pin))
        lockOut()
        assertNull("locked: Exit locked says it, not a tries-left line", gate.attemptsLeft(pin))
        uptimeMs += LOCKOUTS_MS.first()
        assertEquals("after a lockout: a full round", ROUND_TRIES, gate.attemptsLeft(pin))
    }

    // --- right PIN, no PIN, matching ------------------------------------------------------------------------------

    @Test
    fun `no PIN means no on-device exit`() {
        assertFalse(gate.isAvailable(null))
        assertFalse(gate.isAvailable(""))
        assertFalse(gate.isAvailable("   "))
        assertEquals(Outcome.Unavailable, gate.attempt("", null))
        assertEquals(Outcome.Unavailable, gate.attempt("", " "))
        assertNull(gate.remainingLockMs(null))
        assertNull(gate.attemptsLeft(null))
        assertTrue(gate.isAvailable(pin))
    }

    @Test
    fun `the right PIN opens the exit and leaves no count behind`() {
        wrong()
        assertEquals(Outcome.Open, gate.attempt(pin, pin))
        assertEquals(0, store.failures)
        assertNull(gate.attemptsLeft(pin))
    }

    @Test
    fun `a PIN must match exactly`() {
        assertEquals(Outcome.Wrong(4), gate.attempt(pin.dropLast(1), pin))
        assertEquals(Outcome.Wrong(3), gate.attempt("$pin ", pin))
        assertEquals(Outcome.Wrong(2), gate.attempt("0$pin", pin))
    }

    @Test
    fun `full-width digits match the PIN`() {
        assertEquals(Outcome.Open, gate.attempt("４８２１３９", pin))
    }

    @Test
    fun `a legacy PIN of any text still opens the exit`() {
        assertEquals(Outcome.Wrong(4), gate.attempt("letmeout", "Let Me Out!"))
        assertEquals(Outcome.Open, gate.attempt("Let Me Out!", "Let Me Out!"))
        assertEquals(Outcome.Open, gate.attempt("12", "12"))
    }

    @Test
    fun `while locked even the right PIN is not checked`() {
        lockOut()
        uptimeMs += 59_000L
        val o = gate.attempt(pin, pin) as Outcome.Locked
        assertFalse("reported once, not on every attempt", o.started)
        assertEquals(1_000L, o.remainingMs)
        assertEquals(FIRST_ROUND_TRIES, store.failures)
    }

    @Test
    fun `rapid wrong PINs while locked use up nothing`() {
        lockOut()
        repeat(1_000) { assertTrue(wrong() is Outcome.Locked) }
        assertEquals(FIRST_ROUND_TRIES, store.failures)
        uptimeMs += LOCKOUTS_MS.first()
        assertEquals(Outcome.Wrong(ROUND_TRIES - 1), wrong())
    }

    @Test
    fun `the right PIN after a lockout opens the exit and clears the count`() {
        lockOut()
        uptimeMs += LOCKOUTS_MS.first()
        assertNull(gate.remainingLockMs(pin))
        assertEquals(Outcome.Open, gate.attempt(pin, pin))
        assertEquals(0, store.failures)
        assertEquals(Outcome.Wrong(FIRST_ROUND_TRIES - 1), wrong())
    }

    // --- restarts, reboots, clocks ----------------------------------------------------------------------------------

    @Test
    fun `a new process keeps the lockout`() {
        lockOut()
        uptimeMs += 10_000L
        assertEquals(50_000L, ExitPinGate(store) { uptimeMs }.remainingLockMs(pin))
    }

    @Test
    fun `a reboot restarts the lockout instead of ending it`() {
        lockOut()
        uptimeMs = 2_000L // just booted: the uptime clock started again from zero
        val afterReboot = ExitPinGate(store) { uptimeMs }
        assertEquals(60_000L, afterReboot.remainingLockMs(pin))
        uptimeMs += 59_000L
        assertTrue(afterReboot.attempt(pin, pin) is Outcome.Locked)
        uptimeMs += 1_000L
        assertEquals(Outcome.Open, afterReboot.attempt(pin, pin))
    }

    @Test
    fun `rebooting again and again mid-lockout never ends it early`() {
        lockOut()
        repeat(5) {
            uptimeMs = 1_000L // a reboot, then a quick look
            val booted = ExitPinGate(store) { uptimeMs }
            uptimeMs += 30_000L
            assertTrue(booted.attempt(pin, pin) is Outcome.Locked)
        }
        assertEquals(FIRST_ROUND_TRIES, store.failures)
    }

    @Test
    fun `after a reboot the lockout never ends before its full time has really passed`() {
        // Locked at 10 min of uptime for 1 min; after a reboot uptime restarts at 0, so any reading past the old lock
        // time means at least that much real time has passed since boot alone.
        uptimeMs = 10 * 60_000L
        lockOut()
        uptimeMs = 10 * 60_000L + 5_000L // rebooted and has been up a little longer than before
        assertEquals("treated as the same boot: 55 s left", 55_000L, gate.remainingLockMs(pin))
        uptimeMs += 55_000L
        assertNull(gate.remainingLockMs(pin))
    }

    // --- the count belongs to a PIN ---------------------------------------------------------------------------------

    @Test
    fun `a new exit PIN starts the count again, lockout included`() {
        lockOut()
        val newPin = "905172"
        assertNull(gate.remainingLockMs(newPin))
        assertNull(gate.attemptsLeft(newPin))
        assertEquals(Outcome.Wrong(FIRST_ROUND_TRIES - 1), gate.attempt("000000", newPin))
        assertEquals(Outcome.Open, gate.attempt(newPin, newPin))
    }

    @Test
    fun `the same PIN in full-width digits is the same PIN`() {
        lockOut()
        assertNotNull(gate.remainingLockMs("４８２１３９"))
    }

    @Test
    fun `a count saved before the fingerprint counts against whatever PIN is set`() {
        store.attempts = PinAttempts(failures = FIRST_ROUND_TRIES, lastFailureAt = uptimeMs, pinKey = null)
        assertEquals(60_000L, gate.remainingLockMs("905172"))
    }

    // --- clear (the console's Clear PIN lockout) --------------------------------------------------------------------

    @Test
    fun `clear ends a lockout and gives back a full first round`() {
        lockOut()
        uptimeMs += 1_000L
        assertEquals(ExitPinGate.Cleared(failures = FIRST_ROUND_TRIES, wasLocked = true), gate.clear())
        assertNull(gate.remainingLockMs(pin))
        assertEquals(Outcome.Wrong(FIRST_ROUND_TRIES - 1), wrong())
    }

    @Test
    fun `clear with nothing to clear is fine and writes nothing`() {
        store.writable = false
        assertEquals(ExitPinGate.Cleared(failures = 0, wasLocked = false), gate.clear())
    }

    @Test
    fun `clear between rounds says it wasn't locked`() {
        lockOut()
        uptimeMs += LOCKOUTS_MS.first()
        wrong()
        assertEquals(ExitPinGate.Cleared(failures = FIRST_ROUND_TRIES + 1, wasLocked = false), gate.clear())
    }

    @Test
    fun `a clear that can't be saved leaves the lockout in place`() {
        lockOut()
        store.writable = false
        assertNull(gate.clear())
        assertNotNull(gate.remainingLockMs(pin))
    }

    @Test
    fun `a clear after a reboot mid-lockout still clears it`() {
        lockOut()
        uptimeMs = 3_000L
        val booted = ExitPinGate(store) { uptimeMs }
        assertEquals(true, booted.clear()?.wasLocked)
        assertEquals(Outcome.Open, booted.attempt(pin, pin))
    }

    @Test
    fun `a clear racing PIN entry is never lost or half-applied`() {
        // Many threads typing wrong PINs while another clears: every operation is atomic, so the count only ever holds
        // values the curve can reach, and a clear with nothing after it always leaves zero.
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val clears = mutableListOf<ExitPinGate.Cleared?>()
        repeat(7) {
            pool.execute {
                start.await()
                repeat(500) { wrong() }
            }
        }
        pool.execute {
            start.await()
            repeat(500) { gate.clear().let { synchronized(clears) { clears += it } } }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue("every clear was saved", clears.size == 500 && clears.all { it != null })
        assertTrue("never past the first lockout: ${store.failures}", store.failures in 0..FIRST_ROUND_TRIES)
        assertNotNull(gate.clear())
        assertEquals(0, store.failures)
        assertEquals(Outcome.Open, gate.attempt(pin, pin))
    }

    // --- an update from an older agent ------------------------------------------------------------------------------

    @Test
    fun `an older agent's lockout survives the update, never shorter`() {
        // Old curve: locked from the 3rd wrong PIN, each further one stepping 30 s, 1 min, 5 min, 15 min, 1 h.
        val old = listOf(30_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L, 60 * 60_000L)
        old.forEachIndexed { i, oldLockMs ->
            store.attempts = PinAttempts(failures = 3 + i, lastFailureAt = uptimeMs, pinKey = null, schema = 1)
            val left = checkNotNull(gate.remainingLockMs(pin)) { "old count ${3 + i} must stay locked" }
            assertTrue("old count ${3 + i}: $left < $oldLockMs", left >= oldLockMs)
        }
    }

    @Test
    fun `an older agent at its hour step stays at one guess an hour after the update`() {
        store.attempts = PinAttempts(failures = 9, lastFailureAt = uptimeMs, pinKey = null, schema = 1)
        uptimeMs += LOCKOUTS_MS.last()
        assertEquals(1, gate.attemptsLeft(pin))
        assertEquals(LOCKOUTS_MS.last(), (wrong() as Outcome.Locked).remainingMs)
    }

    @Test
    fun `an older agent's count below a lockout keeps counting, with the new room for typos`() {
        store.attempts = PinAttempts(failures = 2, lastFailureAt = uptimeMs, pinKey = null, schema = 1)
        assertNull(gate.remainingLockMs(pin))
        assertEquals(FIRST_ROUND_TRIES - 2, gate.attemptsLeft(pin))
        assertEquals(Outcome.Wrong(FIRST_ROUND_TRIES - 3), wrong())
        assertEquals(PinAttempts.SCHEMA, store.attempts.schema)
    }

    @Test
    fun `an older agent's lockout after a reboot restarts in full`() {
        store.attempts = PinAttempts(failures = 7, lastFailureAt = 10 * 60 * 60_000L, pinKey = null, schema = 1)
        uptimeMs = 1_000L
        assertEquals(60 * 60_000L, gate.remainingLockMs(pin))
        assertEquals("migrated as it is saved", PinAttempts.SCHEMA, store.attempts.schema)
    }

    // --- storage failures -------------------------------------------------------------------------------------------

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
            override fun write(attempts: PinAttempts): Boolean = ++saves == 1 && store.write(attempts)
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
