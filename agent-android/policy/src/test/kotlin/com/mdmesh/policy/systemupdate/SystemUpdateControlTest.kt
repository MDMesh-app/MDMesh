package com.mdmesh.policy.systemupdate

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.ProbeErrorLog
import com.mdmesh.policy.systemupdate.SystemUpdateInstall.Automatic
import com.mdmesh.policy.systemupdate.SystemUpdateInstall.Postpone
import com.mdmesh.policy.systemupdate.SystemUpdateInstall.Windowed
import com.mdmesh.proto.ConfigSystemUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `systemUpdatePolicy` decisions over a fake platform (no Android types): what each section sets, what is refused
 * as unsupported, and when the key is advertised.
 */
class SystemUpdateControlTest {

    /** Records what the platform was asked to set (null = cleared); [refusal] makes every call throw. */
    private class FakePlatform : SystemUpdatePlatform {
        val calls = mutableListOf<SystemUpdateInstall?>()
        var refusal: Exception? = null
        override fun set(install: SystemUpdateInstall?) {
            refusal?.let { throw it }
            calls += install
        }
    }

    private val automatic = ConfigSystemUpdate(ConfigSystemUpdate.AUTOMATIC)

    private fun windowed(start: Int?, end: Int?) =
        ConfigSystemUpdate(ConfigSystemUpdate.WINDOWED, windowStart = start, windowEnd = end)

    @Test fun `each policy sets its install mode`() {
        val p = FakePlatform(); val c = SystemUpdateControl(p) { true }
        assertEquals(PolicyOutcome.Applied, c.apply(automatic))
        assertEquals(PolicyOutcome.Applied, c.apply(windowed(1320, 240)))
        assertEquals(PolicyOutcome.Applied, c.apply(ConfigSystemUpdate(ConfigSystemUpdate.POSTPONE)))
        assertEquals(listOf(Automatic, Windowed(1320, 240), Postpone), p.calls)
    }

    @Test fun `a window may cross midnight and use both ends of the day`() {
        assertEquals(Windowed(1320, 240), SystemUpdateControl.installFor(windowed(1320, 240)))
        assertEquals(Windowed(0, 1439), SystemUpdateControl.installFor(windowed(0, 1439)))
        assertEquals(Windowed(1439, 0), SystemUpdateControl.installFor(windowed(1439, 0)))
    }

    @Test fun `an unknown policy or a bad window is unsupported and sets nothing`() {
        val p = FakePlatform(); val c = SystemUpdateControl(p) { true }
        val refused = listOf(
            ConfigSystemUpdate("none"), ConfigSystemUpdate(), ConfigSystemUpdate("Automatic"),
            windowed(null, null), windowed(null, 240), windowed(1320, null),
            windowed(-1, 240), windowed(1320, 1440), windowed(600, 600),
        )
        for (section in refused) assertEquals("$section", PolicyOutcome.Unsupported, c.apply(section))
        assertTrue(p.calls.isEmpty())
    }

    @Test fun `without Device Owner nothing is set, and it is checked on every call`() {
        var owner = false
        val p = FakePlatform(); val c = SystemUpdateControl(p) { owner }
        assertEquals(PolicyOutcome.Unsupported, c.apply(automatic))
        assertEquals(PolicyOutcome.Unsupported, c.release())
        assertTrue(p.calls.isEmpty())
        owner = true // QR provisioning completes after the process started (#76)
        assertEquals(PolicyOutcome.Applied, c.apply(automatic))
        assertEquals(listOf(Automatic), p.calls)
    }

    @Test fun `release clears the policy`() {
        val p = FakePlatform()
        assertEquals(PolicyOutcome.Applied, SystemUpdateControl(p) { true }.release())
        assertEquals(listOf<SystemUpdateInstall?>(null), p.calls)
    }

    @Test fun `a refused call fails with its reason and never throws`() {
        val p = FakePlatform().apply { refusal = SecurityException("not the device owner") }
        val c = SystemUpdateControl(p) { true }
        assertEquals(PolicyOutcome.Failed("not the device owner"), c.apply(automatic))
        assertEquals(PolicyOutcome.Failed("not the device owner"), c.release())
    }

    @Test fun `a Device Owner check that throws fails (retried), not unsupported (final)`() {
        val c = SystemUpdateControl(FakePlatform()) { error("binder") }
        assertEquals(PolicyOutcome.Failed("binder"), c.apply(automatic))
        assertEquals(PolicyOutcome.Failed("binder"), c.release())
    }

    @Test fun `advertised while it can be set, and a check that throws keeps the last answer`() {
        var owner: () -> Boolean = { false }
        val logged = mutableListOf<String>()
        val log = ProbeErrorLog { message, _ -> logged += message }
        val c = SystemUpdateControl(FakePlatform()) { owner() }
        assertEquals(emptyList<String>(), c.advertisedKeys(log))
        owner = { true }
        assertEquals(listOf("systemUpdatePolicy"), c.advertisedKeys(log))
        owner = { error("binder") }
        assertEquals("an error is not unsupported", listOf("systemUpdatePolicy"), c.advertisedKeys(log))
        owner = { false }
        assertEquals(emptyList<String>(), c.advertisedKeys(log))
        assertEquals(
            listOf("policy check failed: systemUpdatePolicy", "policy check recovered: systemUpdatePolicy"),
            logged,
        )
    }
}
