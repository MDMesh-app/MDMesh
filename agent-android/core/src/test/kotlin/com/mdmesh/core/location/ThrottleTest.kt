package com.mdmesh.core.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottleTest {
    private var uptimeMs = 0L
    private val throttle = Throttle(windowMs = 15 * 60_000L) { uptimeMs }

    @Test fun `the first use is allowed, then not again within the window`() {
        assertTrue(throttle.tryUse())
        uptimeMs += 14 * 60_000L
        assertFalse(throttle.tryUse())
        uptimeMs += 60_000L
        assertTrue(throttle.tryUse())
    }

    @Test fun `a refused use doesn't restart the window`() {
        assertTrue(throttle.tryUse())
        uptimeMs += 10 * 60_000L
        assertFalse(throttle.tryUse())
        uptimeMs += 5 * 60_000L
        assertTrue("15 min after the first use, not after the refused one", throttle.tryUse())
    }

    @Test fun `uptime zero is a real time, not "never"`() {
        assertTrue(throttle.tryUse())
        assertFalse(throttle.tryUse())
    }
}
