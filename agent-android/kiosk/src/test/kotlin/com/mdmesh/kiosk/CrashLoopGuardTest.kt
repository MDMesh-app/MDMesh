package com.mdmesh.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [CrashLoopGuard]'s counting logic, exercised with an
 * [InMemoryFaultStore] and a fake, manually advanced clock — no Android required.
 */
class CrashLoopGuardTest {

    /** Mutable fake clock. */
    private class FakeClock(var nowMs: Long = 0L) {
        val source: () -> Long = { nowMs }
        fun advance(ms: Long) { nowMs += ms }
    }

    private val span = CrashLoopGuard.LOOP_TIME_SPAN // 60_000

    @Test
    fun `first fault starts counter at one and records time`() {
        val store = InMemoryFaultStore()
        val clock = FakeClock(nowMs = 1_000L)
        val guard = CrashLoopGuard(store, clock.source)

        guard.registerFault()

        assertEquals(1, store.counter)
        assertEquals(1_000L, store.lastFaultTime)
        assertFalse(guard.isCrashLoopDetected())
    }

    @Test
    fun `faults within span increment the counter`() {
        val store = InMemoryFaultStore()
        val clock = FakeClock(nowMs = 1_000L)
        val guard = CrashLoopGuard(store, clock.source)

        guard.registerFault()
        clock.advance(10_000L)
        guard.registerFault()
        clock.advance(10_000L)
        guard.registerFault()

        assertEquals(3, store.counter)
        // last-fault time stays the first fault's time (matches the ported algorithm).
        assertEquals(1_000L, store.lastFaultTime)
    }

    @Test
    fun `a fault after the span resets the counter to one`() {
        val store = InMemoryFaultStore()
        val clock = FakeClock(nowMs = 1_000L)
        val guard = CrashLoopGuard(store, clock.source)

        guard.registerFault()
        guard.registerFault()
        assertEquals(2, store.counter)

        // Jump beyond the window: next fault restarts the count.
        clock.advance(span + 1)
        guard.registerFault()

        assertEquals(1, store.counter)
        assertEquals(1_000L + span + 1, store.lastFaultTime)
    }

    @Test
    fun `threshold trips on the fourth fault within the span`() {
        val store = InMemoryFaultStore()
        val clock = FakeClock(nowMs = 0L)
        val guard = CrashLoopGuard(store, clock.source)

        // 3 faults within 60s: counter == 3, NOT yet detected (needs > LOOP_CRASHES).
        repeat(3) {
            guard.registerFault()
            clock.advance(1_000L)
        }
        assertEquals(3, store.counter)
        assertFalse("3 crashes should not trip the guard", guard.isCrashLoopDetected())

        // 4th fault, still within the window from the first fault.
        guard.registerFault()
        assertEquals(4, store.counter)
        assertTrue("4 crashes within span should trip the guard", guard.isCrashLoopDetected())
    }

    @Test
    fun `no faults means no loop`() {
        val guard = CrashLoopGuard(InMemoryFaultStore(), FakeClock(nowMs = 5_000L).source)
        assertFalse(guard.isCrashLoopDetected())
    }

    @Test
    fun `detection aged out of window resets and returns false`() {
        val store = InMemoryFaultStore()
        val clock = FakeClock(nowMs = 0L)
        val guard = CrashLoopGuard(store, clock.source)

        // Trip the guard: 4 crashes within the span.
        repeat(4) { guard.registerFault() }
        assertTrue(guard.isCrashLoopDetected())

        // Let the window elapse: detection should reset, not stay latched.
        clock.advance(span + 1)
        assertFalse(guard.isCrashLoopDetected())
        assertEquals(0, store.counter)
        assertEquals(0L, store.lastFaultTime)
    }
}
