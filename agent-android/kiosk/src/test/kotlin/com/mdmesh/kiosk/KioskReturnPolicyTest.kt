package com.mdmesh.kiosk

import com.mdmesh.kiosk.KioskReturnPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KioskReturnPolicy] on the JVM: an [InMemoryFaultStore] and a manually advanced clock. */
class KioskReturnPolicyTest {

    private var nowMs = 10_000L
    private val store = InMemoryFaultStore()
    private val policy = KioskReturnPolicy(CrashLoopGuard(store) { nowMs })

    @Test
    fun `a few returns reopen the app`() {
        repeat(CrashLoopGuard.LOOP_CRASHES) { assertEquals(Action.REOPEN, policy.onReturned()) }
        assertFalse(policy.held)
    }

    @Test
    fun `closing the app repeatedly holds instead of reopening`() {
        // Back (or Home) in the pinned app, four times within a minute.
        val actions = List(CrashLoopGuard.LOOP_CRASHES + 1) { nowMs += 1_000; policy.onReturned() }
        assertEquals(Action.HOLD, actions.last())
        assertTrue(policy.held)
    }

    @Test
    fun `a hold lasts until someone opens the app again`() {
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { policy.onReturned() }
        // Well past the guard's window: still held, the device doesn't start looping again on its own.
        nowMs += 10 * CrashLoopGuard.LOOP_TIME_SPAN
        assertEquals(Action.HOLD, policy.onReturned())

        policy.retry()
        assertFalse(policy.held)
        assertEquals(Action.REOPEN, policy.onReturned())
        assertEquals(1, store.counter)
    }

    @Test
    fun `a loop that tripped just before a reboot still holds at start`() {
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { policy.onReturned() }
        val afterReboot = KioskReturnPolicy(CrashLoopGuard(store) { nowMs })
        assertEquals(Action.HOLD, afterReboot.onApplied(changed = false))
    }

    @Test
    fun `a hold survives the launcher coming back to the foreground`() {
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { policy.onReturned() }
        nowMs += 10 * CrashLoopGuard.LOOP_TIME_SPAN
        assertEquals(Action.HOLD, policy.onApplied(changed = false))
    }

    @Test
    fun `a different kiosk starts afresh`() {
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { policy.onReturned() }
        assertEquals(Action.REOPEN, policy.onApplied(changed = true))
        assertFalse(policy.held)
    }

    @Test
    fun `an old loop doesn't hold at start`() {
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { policy.onReturned() }
        nowMs += CrashLoopGuard.LOOP_TIME_SPAN + 1
        assertEquals(Action.REOPEN, KioskReturnPolicy(CrashLoopGuard(store) { nowMs }).onApplied(changed = false))
    }

    @Test
    fun `reset forgets every fault`() {
        val guard = CrashLoopGuard(store) { nowMs }
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { guard.registerFault() }
        guard.reset()
        assertEquals(0, store.counter)
        assertEquals(-1L, store.lastFaultTime)
        assertFalse(guard.isCrashLoopDetected())
    }
}
