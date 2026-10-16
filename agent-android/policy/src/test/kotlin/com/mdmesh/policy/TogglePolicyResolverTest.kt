package com.mdmesh.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TogglePolicyResolver] (GitHub #76): Device-Owner status flips to true partway through QR provisioning, after
 * the process built its singletons, so policies are resolved per call; once resolved, every caller gets the same
 * instance (a policy may keep in-memory state).
 */
class TogglePolicyResolverTest {

    private class Toggle(override val capabilityKey: String, private val supported: () -> Boolean) : TogglePolicy {
        override fun isSupported() = supported()
        override fun setEnabled(enabled: Boolean): PolicyOutcome = PolicyOutcome.Applied
    }

    private var deviceOwner = false
    private var probes = 0

    /** Like the strategy factories: a fresh candidate per probe, kept only when supported. */
    private val resolver = TogglePolicyResolver(
        linkedMapOf<String, () -> TogglePolicy?>(
            "camera" to { Toggle("camera") { true } },
            "screenshots" to { probes++; Toggle("screenshots") { deviceOwner }.takeIf { it.isSupported() } },
        ),
    )

    @Test
    fun `a Device Owner policy appears on the first call after Device Owner arrives`() {
        assertEquals(listOf("camera"), resolver.current().keys.toList())
        assertEquals(listOf("camera"), resolver.current().keys.toList())

        deviceOwner = true

        assertEquals(listOf("camera", "screenshots"), resolver.current().keys.toList())
        assertEquals("probed on every call until it resolved", 3, probes)
    }

    @Test
    fun `a resolved policy is the same instance on every call and is not probed again`() {
        deviceOwner = true
        val first = resolver.current().getValue("screenshots")

        repeat(3) { assertSame(first, resolver.current().getValue("screenshots")) }
        assertSame(resolver.current().getValue("camera"), resolver.current().getValue("camera"))
        assertEquals(1, probes)
    }

    @Test
    fun `a policy whose support goes away drops out and comes back as the same instance`() {
        deviceOwner = true
        val first = resolver.current().getValue("screenshots")

        deviceOwner = false
        assertTrue("screenshots" !in resolver.current())

        deviceOwner = true
        assertSame(first, resolver.current().getValue("screenshots"))
        assertEquals(1, probes)
    }
}
