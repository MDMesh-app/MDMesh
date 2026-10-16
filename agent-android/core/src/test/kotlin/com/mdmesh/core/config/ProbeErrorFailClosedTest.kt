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
import com.mdmesh.policy.ReleasablePolicy
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.policy.TogglePolicyResolver
import com.mdmesh.policy.wifi.InMemoryWifiRadioOffState
import com.mdmesh.policy.wifi.WifiRadio
import com.mdmesh.policy.wifi.WifiRadioPolicy
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A policy check that throws (e.g. a DevicePolicyManager binder error) fails closed for that key only: reported
 * failed (retried), never `unsupported` (final, which would save the document and leave a restriction unenforced).
 */
class ProbeErrorFailClosedTest {

    /** Throws from its probe and from its support check while [broken]. */
    private class Flaky {
        var broken = false
        fun check() = check(!broken) { "binder died" }
    }

    private class Toggle(
        override val capabilityKey: String,
        private val flaky: Flaky,
    ) : TogglePolicy, ReleasablePolicy {
        val calls = mutableListOf<Boolean>()
        var releases = 0
        override fun isSupported(): Boolean { flaky.check(); return true }
        override fun setEnabled(enabled: Boolean): PolicyOutcome { calls += enabled; return PolicyOutcome.Applied }
        override fun release(): PolicyOutcome { releases++; return PolicyOutcome.Applied }
    }

    private val flaky = Flaky()
    private val screenshots = Toggle("screenshots", flaky)
    private val camera = Toggle("camera", Flaky())

    private val resolver = TogglePolicyResolver(
        linkedMapOf<String, () -> TogglePolicy?>(
            "screenshots" to { flaky.check(); screenshots },
            "camera" to { camera },
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

    private val doc = ConfigApplyPayload(revision = "r1", policies = mapOf("screenshots" to false, "camera" to false))

    @Test
    fun `a probe error reports the key failed, keeps the document unsaved and applies the other keys`() = runTest {
        val store = InMemoryConfigStateStore()
        val applier = ConfigApplier(resolver::current, kiosk(), {}, store)

        flaky.broken = true
        val r = applier.apply(doc)

        assertEquals(
            ConfigOutcome.failed("policy check error (IllegalStateException)"),
            r.outcomes["policies.screenshots"],
        )
        assertTrue(ConfigOutcome.isFailed(r.outcomes.getValue("policies.screenshots")))
        assertEquals(ConfigOutcome.APPLIED, r.outcomes["policies.camera"])
        assertEquals(listOf(false), camera.calls)
        assertTrue(screenshots.calls.isEmpty())
        assertFalse(ConfigApplier.succeeded(r))
        assertNull("not saved, so the server re-sends it", store.revision())

        flaky.broken = false // the server's re-send, once the probe works again
        val retry = applier.apply(doc)

        assertEquals(ConfigOutcome.APPLIED, retry.outcomes["policies.screenshots"])
        assertEquals(listOf(false), screenshots.calls)
        assertEquals("r1", store.revision())
    }

    @Test
    fun `a kept policy whose check throws later is reported failed, not unsupported`() = runTest {
        val store = InMemoryConfigStateStore()
        val applier = ConfigApplier(resolver::current, kiosk(), {}, store)
        applier.apply(doc)
        assertEquals("r1", store.revision())

        flaky.broken = true
        val r = applier.apply(doc.copy(revision = "r2"))

        assertTrue(ConfigOutcome.isFailed(r.outcomes.getValue("policies.screenshots")))
        assertEquals("r1", store.revision())
        assertEquals("a key that worked before is still advertised", listOf("screenshots", "camera"),
            resolver.current().advertisedKeys)
    }

    @Test
    fun `a key whose check throws is never released, and the release is retried`() = runTest {
        val store = InMemoryConfigStateStore()
        val applier = ConfigApplier(resolver::current, kiosk(), {}, store)
        applier.apply(doc)

        flaky.broken = true
        val unmanage = ConfigApplyPayload(revision = "r2", policies = mapOf("camera" to true))
        val r = applier.apply(unmanage)

        assertEquals(0, screenshots.releases)
        assertTrue(ConfigOutcome.isFailed(r.outcomes.getValue("policies.screenshots")))
        assertEquals("r1", store.revision())

        flaky.broken = false
        val retry = applier.apply(unmanage)

        assertEquals(1, screenshots.releases)
        assertTrue(ConfigApplier.succeeded(retry))
        assertEquals("r2", store.revision())
    }

    @Test
    fun `policy apply fails the command for a key whose check throws`() = runTest {
        val handler = PolicyApplyHandler(resolver::current)
        fun command() = CommandEnvelope(
            commandId = "c1",
            issuedAt = "2026-01-01T00:00:00Z",
            type = "policy.apply",
            payload = buildJsonObject {
                put("policy", "screenshots")
                put("value", false)
            },
        )

        flaky.broken = true
        val failed = handler.handle(command())
        assertEquals(CommandStatus.FAILED, failed.status)
        assertTrue(screenshots.calls.isEmpty())

        flaky.broken = false
        assertEquals(CommandStatus.DONE, handler.handle(command()).status)
        assertEquals(listOf(false), screenshots.calls)
    }

    private class StrandedRadio(var on: Boolean = true) : WifiRadio {
        override fun isEnabled() = on
        override fun setEnabled(enabled: Boolean): Boolean { on = enabled; return true }
        override fun hasNonWifiInternet() = true
    }

    @Test
    fun `a wifiRadio check that throws skips the self-heal tick`() = runTest {
        val radio = StrandedRadio()
        var now = 0L
        val dpm = Flaky()
        val policy = WifiRadioPolicy(radio, { _, _ -> }, 33, InMemoryWifiRadioOffState(), { now }) { dpm.check(); true }
        val toggles = TogglePolicyResolver(linkedMapOf<String, () -> TogglePolicy?>("wifiRadio" to { policy }))
        val applier = ConfigApplier(toggles::current, kiosk(), {}, InMemoryConfigStateStore())
        applier.apply(ConfigApplyPayload(revision = "h1", policies = mapOf("wifiRadio" to false)))
        assertFalse(radio.on)

        now = WifiRadioPolicy.HEAL_AFTER_MS
        dpm.broken = true
        assertFalse(applier.selfHealWifiRadio { null })
        assertFalse("skipped, not healed", radio.on)

        dpm.broken = false
        assertTrue(applier.selfHealWifiRadio { null })
        assertTrue(radio.on)
    }

    /** A wifiRadio policy whose Device-Owner check throws while [dpm] is broken, with its saved state exposed. */
    private class RadioSetup {
        val radio = StrandedRadio()
        val state = InMemoryWifiRadioOffState()
        val dpm = Flaky()
        val policy = WifiRadioPolicy(radio, { _, _ -> }, 33, state) { dpm.check(); true }
    }

    private fun off(revision: String) = ConfigApplyPayload(revision = revision, policies = mapOf("wifiRadio" to false))

    @Test
    fun `a new revision ends a self-heal hold through the kept instance when its check throws`() = runTest {
        val r = RadioSetup()
        val toggles = TogglePolicyResolver(linkedMapOf<String, () -> TogglePolicy?>("wifiRadio" to { r.policy }))
        val applier = ConfigApplier(toggles::current, kiosk(), {}, InMemoryConfigStateStore())
        applier.apply(off("h1"))
        r.state.heldOn = true // a self-heal turned Wi-Fi back on

        r.dpm.broken = true
        val result = applier.apply(off("h2"))

        assertTrue(ConfigOutcome.isFailed(result.outcomes.getValue("policies.wifiRadio")))
        assertFalse("the new configuration ended the hold", r.state.heldOn)
    }

    @Test
    fun `a hold the new revision could not end is ended when that revision is re-sent`() = runTest {
        val r = RadioSetup()
        // Never resolved in this process: the probe itself checks Device Owner, and that throws.
        val toggles = TogglePolicyResolver(
            linkedMapOf<String, () -> TogglePolicy?>("wifiRadio" to { r.policy.takeIf { it.isSupported() } }),
        )
        val store = InMemoryConfigStateStore().apply { delivered = off("h1") } // from before the restart
        val applier = ConfigApplier(toggles::current, kiosk(), {}, store)
        r.state.heldOn = true

        r.dpm.broken = true
        val first = applier.apply(off("h2"))
        assertTrue(ConfigOutcome.isFailed(first.outcomes.getValue("policies.wifiRadio")))
        assertTrue("cannot be ended yet", r.state.heldOn)

        r.dpm.broken = false
        val resend = applier.apply(off("h2"))

        assertFalse("ended on the re-send of the same revision", r.state.heldOn)
        assertEquals(ConfigOutcome.APPLIED, resend.outcomes["policies.wifiRadio"])
        assertFalse(r.radio.on)
    }

    @Test
    fun `a re-send of the revision that was delivered before the upgrade keeps the hold`() = runTest {
        val r = RadioSetup()
        val toggles = TogglePolicyResolver(linkedMapOf<String, () -> TogglePolicy?>("wifiRadio" to { r.policy }))
        // Written by an agent without holdEndedFor: only the delivered document is known.
        val store = InMemoryConfigStateStore().apply { delivered = off("h1") }
        val applier = ConfigApplier(toggles::current, kiosk(), {}, store)
        r.state.heldOn = true

        val resend = applier.apply(off("h1"))

        assertTrue("same configuration: the hold stays", r.state.heldOn)
        assertTrue(resend.outcomes.getValue("policies.wifiRadio").startsWith("deferred:"))
    }
}
