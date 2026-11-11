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

    // --- The hold is persisted (F2): it survives a new policy (activity recreation, process restore) at any age ---

    private val holds = InMemoryHoldStore()
    private var boot = 1L
    private fun policy() = KioskReturnPolicy(CrashLoopGuard(store) { nowMs }, holds, boot = { boot })

    @Test
    fun `a hold survives recreation long after the guard window`() {
        val first = policy()
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { nowMs += 1_000; first.onReturned() }
        assertTrue(first.held)
        nowMs += 10 * CrashLoopGuard.LOOP_TIME_SPAN
        val recreated = policy()
        assertTrue(recreated.held)
        assertEquals(Action.HOLD, recreated.onApplied(changed = false))
        assertEquals(Action.HOLD, recreated.onReturned())
    }

    @Test
    fun `open again clears the saved hold`() {
        val first = policy()
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { first.onReturned() }
        first.retry()
        assertFalse(policy().held)
    }

    @Test
    fun `a reboot ends a hold older than the guard window`() {
        val first = policy()
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { first.onReturned() }
        nowMs += 10 * CrashLoopGuard.LOOP_TIME_SPAN
        boot = 2L
        assertEquals(Action.REOPEN, policy().onApplied(changed = false))
    }

    @Test
    fun `a reboot right after the loop still holds`() {
        val first = policy()
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { first.onReturned() }
        boot = 2L
        assertEquals(Action.HOLD, policy().onApplied(changed = false))
    }

    @Test
    fun `a different kiosk clears the saved hold`() {
        val first = policy()
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { first.onReturned() }
        assertEquals(Action.REOPEN, policy().onApplied(changed = true))
        assertFalse(policy().held)
    }

    // --- N2: no boot count on this ROM: uptime tells boots apart ---

    private var uptime = 500_000L
    private fun noBootCount() = KioskReturnPolicy(CrashLoopGuard(store) { nowMs }, holds, { 0L }) { uptime }

    @Test
    fun `without a boot count a recreation in the same boot keeps the hold`() {
        val first = noBootCount()
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { first.onReturned() }
        nowMs += 10 * CrashLoopGuard.LOOP_TIME_SPAN
        uptime += 10 * CrashLoopGuard.LOOP_TIME_SPAN
        assertEquals(Action.HOLD, noBootCount().onApplied(changed = false))
    }

    @Test
    fun `without a boot count a reboot (uptime back near zero) ends an old hold`() {
        val first = noBootCount()
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { first.onReturned() }
        nowMs += 10 * CrashLoopGuard.LOOP_TIME_SPAN
        uptime = 30_000L
        assertEquals(Action.REOPEN, noBootCount().onApplied(changed = false))
    }
}
