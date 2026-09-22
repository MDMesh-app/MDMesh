package com.mdmesh.core.config

import android.content.ComponentName
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskHomeSwitch
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigLocation
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConfigApplierTest {
    private class FakeToggle(override val capabilityKey: String, private val outcome: PolicyOutcome) : TogglePolicy {
        var last: Boolean? = null
        override fun isSupported() = true
        override fun setEnabled(enabled: Boolean): PolicyOutcome { last = enabled; return outcome }
    }
    private class FakeController(private val enterResult: KioskResult = KioskResult.Ok) : KioskController {
        var enters = 0; var exits = 0
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult { enters++; return enterResult }
        override fun exit(): KioskResult { exits++; return KioskResult.Ok }
        override fun isLocked(context: android.content.Context) = false
        override fun allowedPackages(): List<String> = emptyList()
    }
    private object NoHome : KioskHomeSwitch { override fun setClaimEnabled(enabled: Boolean) {}; override fun showLauncher() {}; override fun showOemHome() {} }

    private fun kiosk(c: KioskController, store: InMemoryKioskStateStore = InMemoryKioskStateStore()) =
        KioskApplier(c, store, NoHome, ComponentName("a", "b"))

    @Test fun `applies present policies only and persists on full success`() = runTest {
        val wifi = FakeToggle("wifi", PolicyOutcome.Applied); val bt = FakeToggle("bluetooth", PolicyOutcome.Applied)
        val store = InMemoryConfigStateStore(); var loc: String? = null
        val r = ConfigApplier(mapOf("wifi" to wifi, "bluetooth" to bt), kiosk(FakeController()), { loc = it }, store)
            .apply(ConfigApplyPayload(revision = "r1", policies = mapOf("wifi" to false), location = ConfigLocation("active")))
        assertEquals(mapOf("policies.wifi" to ConfigOutcome.APPLIED, "kiosk" to ConfigOutcome.APPLIED, "location" to ConfigOutcome.APPLIED), r.outcomes)
        assertEquals(false, wifi.last); assertNull("bluetooth not in doc -> untouched", bt.last)
        assertEquals("active", loc)
        assertEquals("r1", store.revision())
        assertTrue(ConfigApplier.succeeded(r))
    }

    @Test fun `unsupported policy still counts as success and persists`() = runTest {
        val store = InMemoryConfigStateStore()
        val r = ConfigApplier(emptyMap(), kiosk(FakeController()), {}, store)
            .apply(ConfigApplyPayload(revision = "r2", policies = mapOf("usbStorage" to false)))
        assertEquals(ConfigOutcome.UNSUPPORTED, r.outcomes["policies.usbStorage"])
        assertEquals("r2", store.revision())
    }

    @Test fun `a failed key blocks persistence`() = runTest {
        val store = InMemoryConfigStateStore()
        val r = ConfigApplier(mapOf("wifi" to FakeToggle("wifi", PolicyOutcome.Failed("dpm"))), kiosk(FakeController()), {}, store)
            .apply(ConfigApplyPayload(revision = "r3", policies = mapOf("wifi" to true)))
        assertEquals("failed: dpm", r.outcomes["policies.wifi"])
        assertFalse(ConfigApplier.succeeded(r)); assertNull(store.revision())
    }

    @Test fun `kiosk present enters, kiosk absent exits only when the previous config asserted kiosk`() = runTest {
        val c = FakeController(); val kstore = InMemoryKioskStateStore()
        val a = ConfigApplier(emptyMap(), kiosk(c, kstore), {}, InMemoryConfigStateStore())
        a.apply(ConfigApplyPayload(revision = "k1", kiosk = KioskApplyPayload(mode = "single", pinPackage = "com.a")))
        assertEquals(1, c.enters); assertNotNull(kstore.load())
        a.apply(ConfigApplyPayload(revision = "k2"))
        assertEquals("admin turned kiosk off -> exit", 1, c.exits)
        a.apply(ConfigApplyPayload(revision = "k3"))
        assertEquals("already out of kiosk -> no second exit", 1, c.exits)
    }

    @Test fun `a manual kiosk survives a kiosk-off configuration (upgrade safety)`() = runTest {
        val c = FakeController(); val kstore = InMemoryKioskStateStore()
        kstore.save(KioskApplyPayload(mode = "single", pinPackage = "com.manual")) // set by an ad-hoc kiosk.enter
        val a = ConfigApplier(emptyMap(), kiosk(c, kstore), {}, InMemoryConfigStateStore()) // no config ever applied
        val r = a.apply(ConfigApplyPayload(revision = "first"))
        assertEquals(0, c.exits); assertNotNull(kstore.load())
        assertEquals(ConfigOutcome.APPLIED, r.outcomes["kiosk"])
    }

    @Test fun `kiosk unsupported is reported as unsupported not failed`() = runTest {
        val store = InMemoryConfigStateStore()
        val r = ConfigApplier(emptyMap(), kiosk(FakeController(KioskResult.Unsupported)), {}, store)
            .apply(ConfigApplyPayload(revision = "k9", kiosk = KioskApplyPayload()))
        assertEquals(ConfigOutcome.UNSUPPORTED, r.outcomes["kiosk"]); assertEquals("k9", store.revision())
    }

    @Test fun `reapplyPersisted replays the stored document`() = runTest {
        val wifi = FakeToggle("wifi", PolicyOutcome.Applied); val store = InMemoryConfigStateStore()
        store.save(ConfigApplyPayload(revision = "p1", policies = mapOf("wifi" to true)))
        val r = ConfigApplier(mapOf("wifi" to wifi), kiosk(FakeController()), {}, store).reapplyPersisted()
        assertEquals("p1", r?.revision); assertEquals(true, wifi.last)
        assertNull(ConfigApplier(emptyMap(), kiosk(FakeController()), {}, InMemoryConfigStateStore()).reapplyPersisted())
    }
}
