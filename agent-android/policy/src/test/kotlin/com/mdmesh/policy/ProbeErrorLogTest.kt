package com.mdmesh.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeErrorLogTest {

    private var now = 0L
    private val lines = mutableListOf<String>()
    private val log = ProbeErrorLog(intervalMs = 1_000L, clock = { now }) { message, error ->
        lines += if (error == null) message else "$message (${error.javaClass.simpleName})"
    }

    @Test
    fun `logs a repeated error once per interval, a new error class at once, and the recovery once`() {
        repeat(5) { log.failed("screenshots", IllegalStateException("binder died")) }
        now = 999L
        log.failed("screenshots", IllegalStateException("binder died"))
        log.failed("screenshots", SecurityException("not owner")) // a different error: logged at once
        now = 2_000L
        log.failed("screenshots", SecurityException("not owner")) // the interval passed: logged again
        log.failed("camera", IllegalStateException("binder died")) // per key
        log.recovered("screenshots")
        log.recovered("screenshots")
        log.recovered("wifi") // never failed: nothing to say

        assertEquals(
            listOf(
                "policy check failed: screenshots (IllegalStateException)",
                "policy check failed: screenshots (SecurityException)",
                "policy check failed: screenshots (SecurityException)",
                "policy check failed: camera (IllegalStateException)",
                "policy check recovered: screenshots",
            ),
            lines,
        )
    }

    @Test
    fun `an error after a recovery is logged again`() {
        log.failed("camera", IllegalStateException())
        log.recovered("camera")
        log.failed("camera", IllegalStateException())

        assertEquals(3, lines.size)
    }

    @Test
    fun `the resolver reports errors and recoveries`() {
        var broken = true
        val resolver = TogglePolicyResolver(
            linkedMapOf<String, () -> TogglePolicy?>("camera" to { check(!broken); null }),
            onProbeError = log::failed,
            onProbeOk = log::recovered,
        )
        repeat(3) { resolver.current() }
        broken = false
        repeat(3) { resolver.current() }

        assertEquals(
            listOf("policy check failed: camera (IllegalStateException)", "policy check recovered: camera"),
            lines,
        )
    }
}
