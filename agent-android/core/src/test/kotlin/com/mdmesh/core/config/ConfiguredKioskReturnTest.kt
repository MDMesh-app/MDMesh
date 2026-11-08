package com.mdmesh.core.config

import android.content.ComponentName
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskHomeSwitch
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.testing.fixedToggles
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskFeaturesDto
import com.mdmesh.proto.KioskThemeDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredKioskReturnTest {
    private class Controller : KioskController {
        var enterResult: KioskResult = KioskResult.Ok
        var enters = 0
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult {
            enters++
            return enterResult
        }
        override fun exit() = KioskResult.Ok
        override fun isLocked(context: android.content.Context) = false
        override fun allowedPackages(): List<String> = emptyList()
    }

    private object Home : KioskHomeSwitch {
        override fun setClaimEnabled(enabled: Boolean) = Unit
        override fun showLauncher() = Unit
        override fun showOemHome() = Unit
    }

    private val controller = Controller()
    private val active = InMemoryKioskStateStore()
    private val config = InMemoryConfigStateStore()
    private val payload = KioskApplyPayload(
        mode = "single", pinPackage = "com.example.event", allowedPackages = listOf("com.example.event"),
        password = "staff-pin", exitMode = "visible", features = KioskFeaturesDto(lockVolume = true),
        theme = KioskThemeDto(backgroundColor = "#123456"),
    )

    private fun kiosk(store: KioskStateStore = active) =
        KioskApplier(controller, store, Home, ComponentName("agent", "home")) { false }

    @Test fun `return restores the complete saved configuration without applying other sections`() = runTest {
        config.save(ConfigApplyPayload(revision = "saved", kiosk = payload))
        // No server or policy resolution is needed to return, including after the applier is recreated.
        val applier = ConfigApplier({ error("Must not reapply policies") }, kiosk(), {}, config, setPowerMode = {})
        assertEquals(KioskResult.Ok, applier.returnToConfiguredKiosk())
        assertEquals(payload, active.load())
    }

    @Test fun `no configured kiosk never falls back to a manual or default kiosk`() = runTest {
        active.save(payload)
        val applier = ConfigApplier(fixedToggles(), kiosk(), {}, config, setPowerMode = {})
        assertTrue(applier.returnToConfiguredKiosk() is KioskResult.Failed)
        config.save(ConfigApplyPayload(revision = "off"))
        assertTrue(applier.returnToConfiguredKiosk() is KioskResult.Failed)
        assertEquals(0, controller.enters)
    }

    @Test fun `return uses the newer delivered kiosk instead of an older saved kiosk`() = runTest {
        config.save(ConfigApplyPayload(revision = "old", kiosk = payload))
        val replacement = payload.copy(password = "new-pin", pinPackage = "com.example.new")
        config.delivered = ConfigApplyPayload(revision = "new", kiosk = replacement)
        val applier = ConfigApplier(fixedToggles(), kiosk(), {}, config, setPowerMode = {})
        assertEquals(KioskResult.Ok, applier.returnToConfiguredKiosk())
        assertEquals(replacement, active.load())
    }

    @Test fun `delivered kiosk-off prevents return even when another policy fails`() = runTest {
        config.save(ConfigApplyPayload(revision = "old", kiosk = payload))
        val broken = object : TogglePolicy {
            override val capabilityKey = "wifi"
            override fun isSupported() = true
            override fun setEnabled(enabled: Boolean) = PolicyOutcome.Failed("test failure")
        }
        val applier = ConfigApplier(fixedToggles("wifi" to broken), kiosk(), {}, config, setPowerMode = {})
        val result = applier.apply(ConfigApplyPayload(revision = "off", policies = mapOf("wifi" to true)))
        assertFalse(ConfigApplier.succeeded(result))
        assertEquals("old", config.revision())
        assertTrue(applier.returnToConfiguredKiosk() is KioskResult.Failed)
        assertEquals(0, controller.enters)
    }

    @Test fun `failed entry preserves the configuration for a local retry`() = runTest {
        config.save(ConfigApplyPayload(revision = "saved", kiosk = payload))
        val applier = ConfigApplier(fixedToggles(), kiosk(), {}, config, setPowerMode = {})
        controller.enterResult = KioskResult.Failed("test failure")
        assertTrue(applier.returnToConfiguredKiosk() is KioskResult.Failed)
        assertNull(active.load())
        controller.enterResult = KioskResult.Ok
        assertEquals(KioskResult.Ok, applier.returnToConfiguredKiosk())
        assertEquals(payload, active.load())
    }

    @Test fun `a configuration disabling kiosk waits for an in-flight return then exits it`() = runTest {
        config.save(ConfigApplyPayload(revision = "saved", kiosk = payload))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocking = object : KioskStateStore by active {
            override suspend fun save(payload: KioskApplyPayload?) {
                if (payload != null) { entered.complete(Unit); release.await() }
                active.save(payload)
            }
        }
        val applier = ConfigApplier(fixedToggles(), kiosk(blocking), {}, config, setPowerMode = {})
        val returning = launch { assertEquals(KioskResult.Ok, applier.returnToConfiguredKiosk()) }
        entered.await()
        val disabling = launch { applier.apply(ConfigApplyPayload(revision = "off")) }
        release.complete(Unit)
        returning.join(); disabling.join()
        assertNull(active.load())
        assertTrue(applier.returnToConfiguredKiosk() is KioskResult.Failed)
    }
}
