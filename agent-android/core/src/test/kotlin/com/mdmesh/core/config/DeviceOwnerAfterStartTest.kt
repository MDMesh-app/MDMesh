package com.mdmesh.core.config

import android.content.ComponentName
import com.mdmesh.core.command.handlers.PolicyApplyHandler
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskHomeSwitch
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.policy.TogglePolicyResolver
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Regression for GitHub #76: during QR provisioning the agent process starts (and builds its singletons) before
 * it is Device Owner. The applier and the policy.apply handler must still apply the Device-Owner policies once
 * Device Owner arrives, without a process restart. Same root cause as the capability fix in CapabilityCollectorTest.
 */
class DeviceOwnerAfterStartTest {

    /** Stands in for the DevicePolicyManager: Device-Owner status and the calls made on it. */
    private class FakeDpm {
        var deviceOwner = false
        val calls = mutableListOf<String>()
    }

    /** Like ScreenCaptureDisablePolicy / FactoryResetRestrictionPolicy: supported only on a Device Owner. */
    private class DeviceOwnerToggle(override val capabilityKey: String, private val dpm: FakeDpm) : TogglePolicy {
        /** In-memory state, like a policy that counts its own operations: must not be lost between callers. */
        var uses = 0
        override fun isSupported() = dpm.deviceOwner
        override fun setEnabled(enabled: Boolean): PolicyOutcome {
            uses++
            dpm.calls += "$capabilityKey=$enabled"
            return PolicyOutcome.Applied
        }
    }

    /** Mirrors CapabilityRegistry: each probe builds a fresh candidate and keeps it only when supported. */
    private fun resolver(dpm: FakeDpm) = TogglePolicyResolver(
        linkedMapOf<String, () -> TogglePolicy?>(
            "screenshots" to { DeviceOwnerToggle("screenshots", dpm).takeIf { it.isSupported() } },
            "factoryReset" to { DeviceOwnerToggle("factoryReset", dpm).takeIf { it.isSupported() } },
        ),
    )

    private class NoKiosk : KioskController {
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int) =
            KioskResult.Ok
        override fun exit() = KioskResult.Ok
        override fun isLocked(context: android.content.Context) = false
        override fun allowedPackages(): List<String> = emptyList()
    }

    private object NoHome : KioskHomeSwitch {
        override fun setClaimEnabled(enabled: Boolean) = Unit
        override fun showLauncher() = Unit
        override fun showOemHome() = Unit
    }

    private fun kiosk() = KioskApplier(NoKiosk(), InMemoryKioskStateStore(), NoHome, ComponentName("a", "b")) { false }

    private val blockBoth = ConfigApplyPayload(
        revision = "r1",
        policies = mapOf("screenshots" to false, "factoryReset" to false),
    )

    @Test
    fun `config apply applies Device Owner policies when Device Owner arrives after the applier was built`() =
        runTest {
            val dpm = FakeDpm()
            val toggles = resolver(dpm)
            val applier = ConfigApplier(toggles::current, kiosk(), {}, InMemoryConfigStateStore())

            dpm.deviceOwner = true // provisioning completes after the process built its singletons
            val r = applier.apply(blockBoth)

            assertEquals(ConfigOutcome.APPLIED, r.outcomes["policies.screenshots"])
            assertEquals(ConfigOutcome.APPLIED, r.outcomes["policies.factoryReset"])
            assertEquals(listOf("screenshots=false", "factoryReset=false"), dpm.calls)
            // What the agent advertises (CapabilityRegistry.supportedPolicyKeys) is what it just applied.
            assertEquals(listOf("screenshots", "factoryReset"), toggles.current().advertisedKeys)
        }

    @Test
    fun `policy apply applies a Device Owner policy when Device Owner arrives after the handler was built`() =
        runTest {
            val dpm = FakeDpm()
            val handler = PolicyApplyHandler(resolver(dpm)::current)

            dpm.deviceOwner = true
            val result = handler.handle(
                CommandEnvelope(
                    commandId = "c1",
                    issuedAt = "2026-01-01T00:00:00Z",
                    type = "policy.apply",
                    payload = buildJsonObject {
                        put("policy", "screenshots")
                        put("value", false)
                    },
                ),
            )

            assertEquals(CommandStatus.DONE, result.status)
            assertEquals(listOf("screenshots=false"), dpm.calls)
        }

    /**
     * A device that already hit #76 saved its document with the Device-Owner keys `unsupported`. The re-apply after
     * the agent update (ConfigReapplyWorker -> reapplyPersisted) runs them again and they apply.
     */
    @Test
    fun `a document saved while not Device Owner is applied by the re-apply once Device Owner`() = runTest {
        val dpm = FakeDpm()
        val store = InMemoryConfigStateStore()
        val applier = ConfigApplier(resolver(dpm)::current, kiosk(), {}, store)

        val before = applier.apply(blockBoth)
        assertEquals(ConfigOutcome.UNSUPPORTED, before.outcomes["policies.screenshots"])
        assertEquals("unsupported is final: the document is saved", "r1", store.revision())

        dpm.deviceOwner = true
        val after = applier.reapplyPersisted()!!

        assertEquals(ConfigOutcome.APPLIED, after.outcomes["policies.screenshots"])
        assertEquals(ConfigOutcome.APPLIED, after.outcomes["policies.factoryReset"])
        assertEquals(listOf("screenshots=false", "factoryReset=false"), dpm.calls)
    }

    @Test
    fun `config apply and policy apply use the same policy instance`() = runTest {
        val dpm = FakeDpm()
        val toggles = resolver(dpm)
        val applier = ConfigApplier(toggles::current, kiosk(), {}, InMemoryConfigStateStore())
        val handler = PolicyApplyHandler(toggles::current)
        dpm.deviceOwner = true

        val policy = toggles.current().policies.getValue("screenshots")
        applier.apply(blockBoth)
        handler.handle(
            CommandEnvelope(
                commandId = "c2",
                issuedAt = "2026-01-01T00:00:00Z",
                type = "policy.apply",
                payload = buildJsonObject {
                    put("policy", "screenshots")
                    put("value", true)
                },
            ),
        )

        assertSame(policy, toggles.current().policies.getValue("screenshots"))
        assertEquals(2, (policy as DeviceOwnerToggle).uses)
    }
}
