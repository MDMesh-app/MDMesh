package com.mdmesh.core.config

import android.content.ComponentName
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskHomeSwitch
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.core.testing.fixedToggles
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.ReleasablePolicy
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.policy.wifi.InMemoryWifiRadioOffState
import com.mdmesh.policy.wifi.WifiRadio
import com.mdmesh.policy.wifi.WifiRadioPolicy
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
        var exitResult: KioskResult = KioskResult.Ok
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult { enters++; return enterResult }
        override fun exit(): KioskResult { exits++; return exitResult }
        override fun isLocked(context: android.content.Context) = false
        override fun allowedPackages(): List<String> = emptyList()
    }
    private object NoHome : KioskHomeSwitch { override fun setClaimEnabled(enabled: Boolean) {}; override fun showLauncher() {}; override fun showOemHome() {} }

    private fun kiosk(c: KioskController, store: InMemoryKioskStateStore = InMemoryKioskStateStore()) =
        KioskApplier(c, store, NoHome, ComponentName("a", "b")) { false }

    @Test fun `applies present policies only and persists on full success`() = runTest {
        val wifi = FakeToggle("wifi", PolicyOutcome.Applied); val bt = FakeToggle("bluetooth", PolicyOutcome.Applied)
        val store = InMemoryConfigStateStore(); var loc: String? = null
        val r = ConfigApplier(fixedToggles("wifi" to wifi, "bluetooth" to bt), kiosk(FakeController()), { loc = it }, store)
            .apply(ConfigApplyPayload(revision = "r1", policies = mapOf("wifi" to false), location = ConfigLocation("active")))
        assertEquals("no kiosk in the doc and none previously applied -> no kiosk key",
            mapOf("policies.wifi" to ConfigOutcome.APPLIED, "location" to ConfigOutcome.APPLIED), r.outcomes)
        assertEquals(false, wifi.last); assertNull("bluetooth not in doc -> untouched", bt.last)
        assertEquals("active", loc)
        assertEquals("r1", store.revision())
        assertTrue(ConfigApplier.succeeded(r))
    }

    @Test fun `unsupported policy still counts as success and persists`() = runTest {
        val store = InMemoryConfigStateStore()
        val r = ConfigApplier(fixedToggles(), kiosk(FakeController()), {}, store)
            .apply(ConfigApplyPayload(revision = "r2", policies = mapOf("usbStorage" to false)))
        assertEquals(ConfigOutcome.UNSUPPORTED, r.outcomes["policies.usbStorage"])
        assertEquals("r2", store.revision())
    }

    @Test fun `a failed key blocks persistence`() = runTest {
        val store = InMemoryConfigStateStore()
        val r = ConfigApplier(fixedToggles("wifi" to FakeToggle("wifi", PolicyOutcome.Failed("dpm"))), kiosk(FakeController()), {}, store)
            .apply(ConfigApplyPayload(revision = "r3", policies = mapOf("wifi" to true)))
        assertEquals("failed: dpm", r.outcomes["policies.wifi"])
        assertFalse(ConfigApplier.succeeded(r)); assertNull(store.revision())
    }

    @Test fun `kiosk present enters, kiosk absent exits only when the previous config asserted kiosk`() = runTest {
        val c = FakeController(); val kstore = InMemoryKioskStateStore()
        val a = ConfigApplier(fixedToggles(), kiosk(c, kstore), {}, InMemoryConfigStateStore())
        val r1 = a.apply(ConfigApplyPayload(revision = "k1", kiosk = KioskApplyPayload(mode = "single", pinPackage = "com.a")))
        assertEquals(1, c.enters); assertNotNull(kstore.load())
        assertEquals(ConfigOutcome.APPLIED, r1.outcomes["kiosk"])
        val r2 = a.apply(ConfigApplyPayload(revision = "k2"))
        assertEquals("admin turned kiosk off -> exit", 1, c.exits)
        assertEquals("exit is reported", ConfigOutcome.APPLIED, r2.outcomes["kiosk"])
        val r3 = a.apply(ConfigApplyPayload(revision = "k3"))
        assertEquals("already out of kiosk -> no second exit", 1, c.exits)
        assertFalse("nothing ran -> kiosk key omitted", r3.outcomes.containsKey("kiosk"))
    }

    @Test fun `a manual kiosk survives a kiosk-off configuration (upgrade safety)`() = runTest {
        val c = FakeController(); val kstore = InMemoryKioskStateStore()
        kstore.save(KioskApplyPayload(mode = "single", pinPackage = "com.manual")) // set by an ad-hoc kiosk.enter
        // No config ever applied.
        val a = ConfigApplier(fixedToggles(), kiosk(c, kstore), {}, InMemoryConfigStateStore())
        val r = a.apply(ConfigApplyPayload(revision = "first"))
        assertEquals(0, c.exits); assertNotNull(kstore.load())
        assertFalse("nothing asserted or exited -> kiosk key omitted", r.outcomes.containsKey("kiosk"))
    }

    @Test fun `kiosk unsupported is reported as unsupported not failed`() = runTest {
        val store = InMemoryConfigStateStore()
        val r = ConfigApplier(fixedToggles(), kiosk(FakeController(KioskResult.Unsupported)), {}, store)
            .apply(ConfigApplyPayload(revision = "k9", kiosk = KioskApplyPayload()))
        assertEquals(ConfigOutcome.UNSUPPORTED, r.outcomes["kiosk"]); assertEquals("k9", store.revision())
    }

    @Test fun `reapplyPersisted replays the stored document`() = runTest {
        val wifi = FakeToggle("wifi", PolicyOutcome.Applied); val store = InMemoryConfigStateStore()
        store.save(ConfigApplyPayload(revision = "p1", policies = mapOf("wifi" to true)))
        val r = ConfigApplier(fixedToggles("wifi" to wifi), kiosk(FakeController()), {}, store).reapplyPersisted()
        assertEquals("p1", r?.revision); assertEquals(true, wifi.last)
        val empty = ConfigApplier(fixedToggles(), kiosk(FakeController()), {}, InMemoryConfigStateStore())
        assertNull(empty.reapplyPersisted())
    }

    private class FakeReleasable(private val outcome: PolicyOutcome) : TogglePolicy, ReleasablePolicy {
        override val capabilityKey = "wifiRadio"
        var releases = 0
        override fun isSupported() = true
        override fun setEnabled(enabled: Boolean): PolicyOutcome = outcome
        override fun release(): PolicyOutcome { releases++; return PolicyOutcome.Applied }
    }

    @Test fun `a deferred key is reported with its reason and does not block persistence`() = runTest {
        // wifiRadio off refused because Wi-Fi is the only network: the rest of the document must still persist,
        // or a reboot would replay an older document (and a stale kiosk decision).
        val store = InMemoryConfigStateStore()
        val radio = FakeReleasable(PolicyOutcome.Deferred("only network"))
        val r = ConfigApplier(fixedToggles("wifiRadio" to radio), kiosk(FakeController()), {}, store)
            .apply(ConfigApplyPayload(revision = "d1", policies = mapOf("wifiRadio" to false)))
        assertEquals("deferred: only network", r.outcomes["policies.wifiRadio"])
        assertTrue(ConfigApplier.succeeded(r))
        assertEquals("d1", store.revision())
    }

    @Test fun `a key the configuration stops managing is released once`() = runTest {
        val store = InMemoryConfigStateStore(); val radio = FakeReleasable(PolicyOutcome.Applied)
        val a = ConfigApplier(fixedToggles("wifiRadio" to radio), kiosk(FakeController()), {}, store)
        a.apply(ConfigApplyPayload(revision = "w1", policies = mapOf("wifiRadio" to false)))
        assertEquals(0, radio.releases)
        val r = a.apply(ConfigApplyPayload(revision = "w2"))
        assertEquals("Off -> Not managed lifts what Off left behind", 1, radio.releases)
        assertTrue(ConfigApplier.succeeded(r)); assertEquals("w2", store.revision())
        a.apply(ConfigApplyPayload(revision = "w3"))
        assertEquals("not managed before either -> nothing to release", 1, radio.releases)
    }

    private class StrandableRadio(var on: Boolean = true, var other: Boolean = true) : WifiRadio {
        override fun isEnabled() = on
        override fun setEnabled(enabled: Boolean): Boolean { on = enabled; return true }
        override fun hasNonWifiInternet() = other
    }

    @Test fun `wifiRadio self-heal reports a stale revision until the server re-sends, across a boot`() = runTest {
        // Cellular stays up, but the server is only reachable over Wi-Fi: no check-in gets through.
        val radio = StrandableRadio(); val state = InMemoryWifiRadioOffState(); val store = InMemoryConfigStateStore()
        var now = 0L
        val policy = WifiRadioPolicy(radio, { _, _ -> }, 33, state, { now }) { true }
        val a = ConfigApplier(fixedToggles("wifiRadio" to policy), kiosk(FakeController()), {}, store)
        val doc = ConfigApplyPayload(revision = "h1", policies = mapOf("wifiRadio" to false))
        a.apply(doc)
        assertEquals(false, radio.on); assertEquals("h1", store.revision())

        now = WifiRadioPolicy.HEAL_AFTER_MS - 1
        assertFalse(a.selfHealWifiRadio { null })
        now = WifiRadioPolicy.HEAL_AFTER_MS
        assertTrue(a.selfHealWifiRadio { null })
        assertEquals("Wi-Fi back on", true, radio.on)
        assertEquals("never matches, so the server re-sends config.apply", ConfigStateStore.STALE_REVISION,
            store.revision())
        assertEquals("the document is kept for the boot re-apply", doc, store.load())

        // Boot: the re-apply must neither strand the device again nor claim the revision as applied.
        val boot = a.reapplyPersisted()!!
        assertTrue(boot.outcomes["policies.wifiRadio"]!!.startsWith("deferred: no connection to the server"))
        assertEquals(true, radio.on)
        assertEquals(ConfigStateStore.STALE_REVISION, store.revision())

        // The server's re-send of the same configuration reports the deferral and is persisted as usual.
        val resent = a.apply(doc)
        assertTrue(resent.outcomes["policies.wifiRadio"]!!.startsWith("deferred: no connection to the server"))
        assertEquals(true, radio.on)
        assertEquals("h1", store.revision())

        // The admin changes the configuration: the hold ends and Off applies again.
        val changed = a.apply(doc.copy(revision = "h2"))
        assertEquals(ConfigOutcome.APPLIED, changed.outcomes["policies.wifiRadio"])
        assertEquals(false, radio.on)
    }

    @Test fun `a document with a failed section keeps the hold on its re-send (no off-heal loop)`() = runTest {
        // Stored A; the admin publishes B = Off plus a kiosk that fails. B is never saved, so the hold must belong to
        // B itself, or every re-send of B would end it and turn Wi-Fi off again.
        val radio = StrandableRadio(); val state = InMemoryWifiRadioOffState(); val store = InMemoryConfigStateStore()
        var now = 0L
        val policy = WifiRadioPolicy(radio, { _, _ -> }, 33, state, { now }) { true }
        val failingKiosk = kiosk(FakeController(KioskResult.Failed("no app")))
        val a = ConfigApplier(fixedToggles("wifiRadio" to policy), failingKiosk, {}, store)
        a.apply(ConfigApplyPayload(revision = "A"))
        val b = ConfigApplyPayload(revision = "B", policies = mapOf("wifiRadio" to false), kiosk = KioskApplyPayload())
        assertFalse(ConfigApplier.succeeded(a.apply(b)))
        assertEquals(false, radio.on); assertEquals("A", store.load()?.revision)

        now = WifiRadioPolicy.HEAL_AFTER_MS
        assertTrue(a.selfHealWifiRadio { null })
        val resent = a.apply(b)
        assertTrue(resent.outcomes["policies.wifiRadio"]!!.startsWith("deferred: no connection to the server"))
        assertEquals(true, radio.on)
        a.reapplyPersisted() // boot: replays A, which does not end the hold either
        assertTrue(a.apply(b).outcomes["policies.wifiRadio"]!!.startsWith("deferred"))
        assertEquals(true, radio.on)

        a.apply(b.copy(revision = "C"))
        assertEquals("a changed configuration applies Off again", false, radio.on)
    }

    @Test fun `a key from a delivered but unsaved document is still released when the next one drops it`() = runTest {
        // Saved A has no wifiRadio. B turns Wi-Fi off (and locks it) but bluetooth fails, so B is never saved. C drops
        // wifiRadio: the lock B left must still be lifted, or Wi-Fi stays off and locked for good.
        val radio = StrandableRadio(); val locks = mutableSetOf<String>(); val store = InMemoryConfigStateStore()
        val policy = WifiRadioPolicy(radio, { k, add -> if (add) locks += k else locks -= k }, 33) { true }
        val bt = FakeToggle("bluetooth", PolicyOutcome.Failed("dpm said no"))
        val toggles = fixedToggles("wifiRadio" to policy, "bluetooth" to bt)
        val a = ConfigApplier(toggles, kiosk(FakeController()), {}, store)
        a.apply(ConfigApplyPayload(revision = "A"))
        val offAndBluetooth = mapOf("wifiRadio" to false, "bluetooth" to false)
        val b = a.apply(ConfigApplyPayload(revision = "B", policies = offAndBluetooth))
        assertFalse(ConfigApplier.succeeded(b)); assertEquals("A", store.revision())
        assertEquals(false, radio.on); assertTrue(locks.isNotEmpty())

        assertTrue(ConfigApplier.succeeded(a.apply(ConfigApplyPayload(revision = "C"))))
        assertTrue("B's lock lifted", locks.isEmpty())
    }

    private class FlakyRelease : TogglePolicy, ReleasablePolicy {
        override val capabilityKey = "wifiRadio"
        var releaseOutcome: PolicyOutcome = PolicyOutcome.Applied
        var releases = 0
        override fun isSupported() = true
        override fun setEnabled(enabled: Boolean): PolicyOutcome = PolicyOutcome.Applied
        override fun release(): PolicyOutcome { releases++; return releaseOutcome }
    }

    @Test fun `a failed release is retried by the re-send of the same document`() = runTest {
        // Saved A without wifiRadio; B (wifiRadio Off) fails elsewhere, unsaved; C drops wifiRadio but its release
        // fails. The re-send of C must release it again, though neither A nor C mentions wifiRadio.
        val radio = FlakyRelease(); val store = InMemoryConfigStateStore()
        val bt = FakeToggle("bluetooth", PolicyOutcome.Failed("dpm said no"))
        val a = ConfigApplier(fixedToggles("wifiRadio" to radio, "bluetooth" to bt), kiosk(FakeController()), {}, store)
        a.apply(ConfigApplyPayload(revision = "A"))
        a.apply(ConfigApplyPayload(revision = "B", policies = mapOf("wifiRadio" to false, "bluetooth" to false)))
        radio.releaseOutcome = PolicyOutcome.Failed("busy")
        val c = ConfigApplyPayload(revision = "C")
        assertFalse(ConfigApplier.succeeded(a.apply(c)))
        radio.releaseOutcome = PolicyOutcome.Applied
        assertTrue(ConfigApplier.succeeded(a.apply(c)))
        assertEquals("released again on the re-send", 2, radio.releases)
        a.apply(c)
        assertEquals("and only until it worked", 2, radio.releases)
    }

    @Test fun `a failed kiosk exit is retried by the re-send of the same document`() = runTest {
        val c = FakeController(); val kstore = InMemoryKioskStateStore(); val store = InMemoryConfigStateStore()
        val bt = FakeToggle("bluetooth", PolicyOutcome.Failed("dpm said no"))
        val a = ConfigApplier(fixedToggles("bluetooth" to bt), kiosk(c, kstore), {}, store)
        a.apply(ConfigApplyPayload(revision = "A"))
        val kioskOn = KioskApplyPayload(mode = "single", pinPackage = "com.a")
        a.apply(ConfigApplyPayload(revision = "B", kiosk = kioskOn, policies = mapOf("bluetooth" to false)))
        assertNotNull("B entered kiosk, but is not saved", kstore.load()); assertEquals("A", store.revision())
        c.exitResult = KioskResult.Failed("lock task busy")
        val off = ConfigApplyPayload(revision = "C")
        assertFalse(ConfigApplier.succeeded(a.apply(off)))
        c.exitResult = KioskResult.Ok
        assertTrue(ConfigApplier.succeeded(a.apply(off)))
        assertEquals(2, c.exits); assertNull(kstore.load())
    }

    @Test fun `no Wi-Fi radio policy, nothing to heal`() = runTest {
        val store = InMemoryConfigStateStore()
        store.save(ConfigApplyPayload(revision = "n1"))
        assertFalse(ConfigApplier(fixedToggles(), kiosk(FakeController()), {}, store).selfHealWifiRadio { null })
        assertEquals("n1", store.revision())
    }
}
