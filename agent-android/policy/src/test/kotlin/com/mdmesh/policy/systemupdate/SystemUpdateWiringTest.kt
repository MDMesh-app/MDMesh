package com.mdmesh.policy.systemupdate

import android.app.admin.SystemUpdatePolicy
import com.mdmesh.policy.CapabilityRegistry
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.policy.TogglePolicyResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the control is wired to: each of these compiles and reports `applied` when it is wrong. The JVM half;
 * DevicePolicyEffectTest reads the same window and capability back from a real Device Owner phone.
 */
class SystemUpdateWiringTest {

    private object Camera : TogglePolicy {
        override val capabilityKey = "camera"
        override fun isSupported() = true
        override fun setEnabled(enabled: Boolean) = PolicyOutcome.Applied
    }

    private val toggles =
        TogglePolicyResolver(linkedMapOf<String, () -> TogglePolicy?>("camera" to { Camera })).current()

    /** The source of the file at [path] under the project root, without comment lines. */
    private fun source(path: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return File(root, path).readLines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
    }

    @Test fun `a window reaches the platform with its start and end in that order`() {
        val policy = requireNotNull(DpmSystemUpdatePlatform.policyFor(SystemUpdateInstall.Windowed(1320, 240)))
        assertEquals(SystemUpdatePolicy.TYPE_INSTALL_WINDOWED, policy.policyType)
        assertEquals(1320, policy.installWindowStart)
        assertEquals(240, policy.installWindowEnd)
    }

    @Test fun `each install maps to its platform policy, and none clears it`() {
        assertEquals(
            SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC,
            DpmSystemUpdatePlatform.policyFor(SystemUpdateInstall.Automatic)?.policyType,
        )
        assertEquals(
            SystemUpdatePolicy.TYPE_POSTPONE,
            DpmSystemUpdatePlatform.policyFor(SystemUpdateInstall.Postpone)?.policyType,
        )
        assertNull(DpmSystemUpdatePlatform.policyFor(null))
    }

    @Test fun `the capability is advertised beside the toggles while it can be set`() {
        val owner = SystemUpdateControl({}) { true }
        assertEquals(listOf("camera", "systemUpdatePolicy"), CapabilityRegistry.policyKeys(toggles, owner, null))
        val notOwner = SystemUpdateControl({}) { false }
        assertEquals(listOf("camera"), CapabilityRegistry.policyKeys(toggles, notOwner, null))
    }

    @Test fun `the registry advertises through policyKeys`() {
        val registry = source("policy/src/main/kotlin/com/mdmesh/policy/CapabilityRegistry.kt")
        assertTrue(registry.contains("fun supportedPolicyKeys(): List<String> = policyKeys(togglePolicies(), systemUpdate,"))
    }

    @Test fun `the agent passes the registry's control to the config applier`() {
        // ConfigApplier defaults it to null, so leaving it out compiles and every section reports unsupported.
        val provider = source("app/src/main/kotlin/com/mdmesh/agent/di/AgentModule.kt")
            .substringAfter("fun provideConfigApplier(").substringBefore("@Provides")
        assertTrue(provider.contains("ConfigApplier("))
        assertTrue(Regex("""\bsystemUpdate\s*=\s*registry\.systemUpdate\b""").containsMatchIn(provider))
    }
}
