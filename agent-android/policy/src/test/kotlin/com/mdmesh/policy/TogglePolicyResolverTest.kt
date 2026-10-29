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
        assertEquals(listOf("camera"), resolver.current().policies.keys.toList())
        assertEquals(listOf("camera"), resolver.current().policies.keys.toList())

        deviceOwner = true

        assertEquals(listOf("camera", "screenshots"), resolver.current().policies.keys.toList())
        assertEquals("probed on every call until it resolved", 3, probes)
    }

    @Test
    fun `a resolved policy is the same instance on every call and is not probed again`() {
        deviceOwner = true
        val first = resolver.current().policies.getValue("screenshots")

        repeat(3) { assertSame(first, resolver.current().policies.getValue("screenshots")) }
        assertSame(resolver.current().policies.getValue("camera"), resolver.current().policies.getValue("camera"))
        assertEquals(1, probes)
    }

    @Test
    fun `a policy whose support goes away drops out and comes back as the same instance`() {
        deviceOwner = true
        val first = resolver.current().policies.getValue("screenshots")

        deviceOwner = false
        assertTrue("screenshots" !in resolver.current())

        deviceOwner = true
        assertSame(first, resolver.current().policies.getValue("screenshots"))
        assertEquals(1, probes)
    }

    @Test
    fun `a throwing probe marks only its key errored, is reported and is tried again`() {
        var failing = true
        val errors = mutableListOf<String>()
        val r = TogglePolicyResolver(
            linkedMapOf<String, () -> TogglePolicy?>(
                "camera" to { Toggle("camera") { true } },
                "screenshots" to { check(!failing) { "binder died" }; Toggle("screenshots") { true } },
                "bluetooth" to { Toggle("bluetooth") { true } },
            ),
            onProbeError = { key, error -> errors += "$key: ${error.message}" },
        )

        assertEquals(listOf("camera", "bluetooth"), r.current().policies.keys.toList())
        assertEquals(listOf("screenshots: binder died"), errors)
        assertEquals("errored, not unsupported", setOf("screenshots"), r.current().errors.keys)
        assertEquals("never resolved: not advertised", listOf("camera", "bluetooth"), r.current().advertisedKeys)

        failing = false
        assertEquals(listOf("camera", "screenshots", "bluetooth"), r.current().policies.keys.toList())
    }

    @Test
    fun `a kept policy whose support check throws is errored for that call only`() {
        var failing = false
        val errors = mutableListOf<String>()
        val r = TogglePolicyResolver(
            linkedMapOf<String, () -> TogglePolicy?>(
                "camera" to { Toggle("camera") { check(!failing) { "binder died" }; true } },
                "bluetooth" to { Toggle("bluetooth") { true } },
            ),
            onProbeError = { key, _ -> errors += key },
        )
        val camera = r.current().policies.getValue("camera")

        failing = true
        val read = r.current()
        assertEquals(listOf("bluetooth"), read.policies.keys.toList())
        assertEquals(listOf("camera"), errors)
        assertSame("the kept instance travels with the error", camera, read.errors.getValue("camera").kept)
        assertEquals("resolved before: still advertised", listOf("camera", "bluetooth"), read.advertisedKeys)

        failing = false
        assertSame(camera, r.current().policies.getValue("camera"))
    }
}
