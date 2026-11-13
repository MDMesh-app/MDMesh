package com.mdmesh.kiosk

import com.mdmesh.kiosk.KioskReturnPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** A ROM without BOOT_COUNT (boot() == 0): a hold from before a reboot never comes back. */
class KioskHoldRebootTest {
    private var nowMs = 10_000L
    private var uptime = 300_000L
    private val faults = InMemoryFaultStore()
    private val holds = InMemoryHoldStore()

    private fun policy() = KioskReturnPolicy(CrashLoopGuard(faults) { nowMs }, holds, { 0L }, { uptime })

    @Test fun `a hold from before a reboot doesn't come back once uptime passes it`() {
        val p = policy()
        repeat(CrashLoopGuard.LOOP_CRASHES + 1) { nowMs += 1_000; p.onReturned() }
        // reboot, long after the loop
        nowMs += 10 * CrashLoopGuard.LOOP_TIME_SPAN
        uptime = 20_000L
        val after = policy()
        assertEquals(Action.REOPEN, after.onApplied(changed = false))
        // the healthy app runs for 10 minutes, the user presses Home once
        uptime = 900_000L
        nowMs += 600_000L
        assertEquals("single return after reboot on a healthy app", Action.REOPEN, after.onReturned())
    }
}
