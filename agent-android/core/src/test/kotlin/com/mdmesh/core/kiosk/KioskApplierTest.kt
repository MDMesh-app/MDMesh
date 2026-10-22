package com.mdmesh.core.kiosk

import android.content.ComponentName
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskFeaturesDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KioskApplierTest {
    private class FakeController(
        private var enterResult: KioskResult,
        private val exitResult: KioskResult = KioskResult.Ok,
        var volumeResult: KioskResult = KioskResult.Ok,
    ) : KioskController {
        var entered: List<String>? = null; var exited = false
        /** Every enter call's allowlist, in order; [failFor] makes an enter with that allowlist fail. */
        val enters = mutableListOf<List<String>>()
        var failFor: List<String>? = null
        /** Every setVolumeLocked call, in order. */
        val volume = mutableListOf<Boolean>()
        /** Every setSafeModeBlocked call, in order. */
        val safeMode = mutableListOf<Boolean>()
        var safeModeResult: KioskResult = KioskResult.Ok
        override fun setSafeModeBlocked(blocked: Boolean): KioskResult { safeMode += blocked; return safeModeResult }
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult {
            entered = allowedPackages; enters += allowedPackages
            return if (allowedPackages == failFor) KioskResult.Failed("dpm") else enterResult
        }
        override fun exit(): KioskResult { exited = true; return exitResult }
        override fun setVolumeLocked(locked: Boolean): KioskResult { volume += locked; return volumeResult }
        override fun isLocked(context: android.content.Context): Boolean = false
        override fun allowedPackages(): List<String> = entered ?: emptyList()
    }
    private class FakeHome : KioskHomeSwitch {
        val log = mutableListOf<String>()
        override fun setClaimEnabled(enabled: Boolean) { log += "claim:$enabled" }
        override fun showLauncher() { log += "launcher" }
        override fun showOemHome() { log += "oem" }
    }
    private val home = ComponentName("com.mdmesh.agent", "com.mdmesh.agent.KioskHomeAlias")
    private fun setting(value: Boolean?) = object : SafeModeSetting { override fun current() = value }

    @Test fun `enter ok persists payload and shows launcher`() = runTest {
        val c = FakeController(KioskResult.Ok); val h = FakeHome(); val store = InMemoryKioskStateStore()
        val r = KioskApplier(c, store, h, home) { false }
            .enter(KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a")))
        assertEquals(KioskResult.Ok, r)
        assertEquals(listOf("com.a"), c.entered)
        assertEquals("com.a", store.load()?.pinPackage)
        assertEquals(listOf("claim:true", "launcher"), h.log)
    }
    @Test fun `enter unsupported reverts the home claim and persists nothing`() = runTest {
        val h = FakeHome(); val store = InMemoryKioskStateStore()
        val r = KioskApplier(FakeController(KioskResult.Unsupported), store, h, home) { false }
            .enter(KioskApplyPayload())
        assertEquals(KioskResult.Unsupported, r)
        assertNull(store.load())
        assertEquals(listOf("claim:true", "claim:false"), h.log)
    }
    @Test fun `a failed entry on a device in kiosk keeps it in kiosk with the kiosk it had`() = runTest {
        val p = KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a"))
        val c = FakeController(KioskResult.Ok); val h = FakeHome(); val store = InMemoryKioskStateStore(); store.save(p)
        c.failFor = listOf("com.b")
        val r = KioskApplier(c, store, h, home) { true }
            .enter(KioskApplyPayload(mode = "single", pinPackage = "com.b", allowedPackages = listOf("com.b")))
        assertEquals(KioskResult.Failed("dpm"), r)
        assertEquals("the old kiosk is put back", listOf(listOf("com.b"), listOf("com.a")), c.enters)
        assertEquals(p, store.load())
        assertFalse("HOME stays claimed, so a restart comes back to kiosk", "claim:false" in h.log)
    }
    @Test fun `entering kiosk blocks Safe Mode unless the configuration allows it`() = runTest {
        val p = KioskApplyPayload(mode = "single", pinPackage = "com.a")
        val c = FakeController(KioskResult.Ok)
        KioskApplier(c, InMemoryKioskStateStore(), FakeHome(), home, setting(null)) { false }.enter(p)
        assertEquals("kiosk.enter from the console, configuration silent", listOf(true), c.safeMode)

        val allowed = FakeController(KioskResult.Ok)
        KioskApplier(allowed, InMemoryKioskStateStore(), FakeHome(), home, setting(true)) { false }.enter(p)
        assertEquals(listOf(false), allowed.safeMode)
    }
    @Test fun `staying in kiosk re-asserts the Safe Mode block`() = runTest {
        val p = KioskApplyPayload(mode = "single", pinPackage = "com.a")
        val c = FakeController(KioskResult.Ok); val store = InMemoryKioskStateStore(); store.save(p)
        assertEquals(KioskResult.Ok, KioskApplier(c, store, FakeHome(), home, setting(null)) { true }.enter(p))
        assertNull("no re-entry", c.entered)
        assertEquals(listOf(true), c.safeMode)
    }
    @Test fun `a Safe Mode block that fails is reported`() = runTest {
        val c = FakeController(KioskResult.Ok); c.safeModeResult = KioskResult.Failed("dpm")
        val r = KioskApplier(c, InMemoryKioskStateStore(), FakeHome(), home, setting(null)) { false }
            .enter(KioskApplyPayload(mode = "single", pinPackage = "com.a"))
        assertEquals(KioskResult.Failed("dpm"), r)
    }
    @Test fun `leaving kiosk lifts the Safe Mode block unless the configuration blocks it`() = runTest {
        val p = KioskApplyPayload(mode = "single", pinPackage = "com.a")
        val c = FakeController(KioskResult.Ok); val store = InMemoryKioskStateStore(); store.save(p)
        KioskApplier(c, store, FakeHome(), home, setting(null)) { true }.exit()
        assertEquals(listOf(false), c.safeMode)

        val blocked = FakeController(KioskResult.Ok); val store2 = InMemoryKioskStateStore(); store2.save(p)
        KioskApplier(blocked, store2, FakeHome(), home, setting(false)) { true }.exit()
        assertEquals("the configuration's own Block stays", listOf(true), blocked.safeMode)
    }
    @Test fun `a failed entry on a device in kiosk keeps Safe Mode blocked`() = runTest {
        val p = KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a"))
        val c = FakeController(KioskResult.Ok); val store = InMemoryKioskStateStore(); store.save(p)
        c.failFor = listOf("com.b")
        KioskApplier(c, store, FakeHome(), home, setting(null)) { true }
            .enter(KioskApplyPayload(mode = "single", pinPackage = "com.b", allowedPackages = listOf("com.b")))
        assertEquals(listOf(true), c.safeMode)
    }
    @Test fun `a lift that fails is not reported, a block that fails is`() = runTest {
        val c = FakeController(KioskResult.Ok); c.safeModeResult = KioskResult.Failed("dpm")
        val store = InMemoryKioskStateStore()
        val a = KioskApplier(c, store, FakeHome(), home, setting(null)) { false }
        assertEquals("lifting failed: Safe Mode stays blocked, which is safe", KioskResult.Ok, a.syncSafeMode())
        store.save(KioskApplyPayload(mode = "single", pinPackage = "com.a"))
        assertEquals(KioskResult.Failed("dpm"), a.syncSafeMode())
    }
    @Test fun `syncSafeMode follows the saved kiosk and the configuration's setting`() = runTest {
        val c = FakeController(KioskResult.Ok); val store = InMemoryKioskStateStore()
        var policy: Boolean? = null
        val setting = object : SafeModeSetting { override fun current() = policy }
        val a = KioskApplier(c, store, FakeHome(), home, setting) { false }
        a.syncSafeMode()
        store.save(KioskApplyPayload(mode = "single", pinPackage = "com.a"))
        a.syncSafeMode()
        policy = true
        a.syncSafeMode()
        assertEquals(listOf(false, true, false), c.safeMode)
    }
    @Test fun `re-entering with the same payload while locked is a no-op`() = runTest {
        val p = KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a"))
        val c = FakeController(KioskResult.Ok); val h = FakeHome(); val store = InMemoryKioskStateStore(); store.save(p)
        assertEquals(KioskResult.Ok, KioskApplier(c, store, h, home) { true }.enter(p))
        assertNull("no lock-task re-entry", c.entered)
        assertTrue("no launcher relaunch (no flicker)", h.log.isEmpty())
    }
    @Test fun `same payload re-enters when lock task is not active, changed payload always re-enters`() = runTest {
        val p = KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a"))
        val store = InMemoryKioskStateStore(); store.save(p)
        val notLocked = FakeController(KioskResult.Ok)
        KioskApplier(notLocked, store, FakeHome(), home) { false }.enter(p)
        assertEquals(listOf("com.a"), notLocked.entered)
        val changed = FakeController(KioskResult.Ok)
        val widened = p.copy(allowedPackages = listOf("com.a", "com.b"))
        KioskApplier(changed, store, FakeHome(), home) { true }.enter(widened)
        assertEquals(listOf("com.a", "com.b"), changed.entered)
    }
    @Test fun `lockVolume locks volume while in kiosk, absent releases it`() = runTest {
        val locked = KioskApplyPayload(
            mode = "single", pinPackage = "com.a", features = KioskFeaturesDto(lockVolume = true),
        )
        val c = FakeController(KioskResult.Ok); val store = InMemoryKioskStateStore()
        assertEquals(KioskResult.Ok, KioskApplier(c, store, FakeHome(), home) { false }.enter(locked))
        assertEquals(listOf(true), c.volume)

        // lockButtons alone never touches volume (DISALLOW_ADJUST_VOLUME mutes the device): released, not locked.
        val buttonsOnly = locked.copy(features = KioskFeaturesDto(lockButtons = true))
        KioskApplier(c, store, FakeHome(), home) { false }.enter(buttonsOnly)
        assertEquals(listOf(true, false), c.volume)
    }

    @Test fun `exit releases the volume lock`() = runTest {
        val c = FakeController(KioskResult.Ok); val store = InMemoryKioskStateStore()
        store.save(KioskApplyPayload(features = KioskFeaturesDto(lockVolume = true)))
        assertEquals(KioskResult.Ok, KioskApplier(c, store, FakeHome(), home) { false }.exit())
        assertEquals(listOf(false), c.volume)
    }

    @Test fun `a failed volume lock keeps a consistent kiosk state and is retried on the next apply`() = runTest {
        val p = KioskApplyPayload(
            mode = "single", pinPackage = "com.a", features = KioskFeaturesDto(lockVolume = true),
        )
        val c = FakeController(KioskResult.Ok, volumeResult = KioskResult.Failed("dpm"))
        val store = InMemoryKioskStateStore()
        assertEquals(KioskResult.Failed("dpm"), KioskApplier(c, store, FakeHome(), home) { false }.enter(p))
        assertEquals("the device IS in kiosk: state says so (no half-entered kiosk)", p, store.load())

        // Next apply of the same payload while locked: no re-entry, but the volume lock is re-asserted.
        c.entered = null; c.volumeResult = KioskResult.Ok
        val h = FakeHome()
        assertEquals(KioskResult.Ok, KioskApplier(c, store, h, home) { true }.enter(p))
        assertNull("no lock-task re-entry", c.entered)
        assertTrue("no launcher relaunch", h.log.isEmpty())
        assertEquals(listOf(true, true), c.volume)
    }

    @Test fun `on-device exit releases volume even when the controller cannot exit`() = runTest {
        // The admin-PIN exit must always get the user out: forced, it clears local state and the volume lock.
        val c = FakeController(KioskResult.Ok, exitResult = KioskResult.Failed("dpm")); val h = FakeHome()
        val store = InMemoryKioskStateStore()
        store.save(KioskApplyPayload(features = KioskFeaturesDto(lockVolume = true)))
        KioskApplier(c, store, h, home) { true }.exit(force = true)
        assertEquals(listOf(false), c.volume)
        assertNull(store.load())
        assertEquals(listOf("claim:false", "oem"), h.log)
    }

    @Test fun `an exit that keeps the agent as HOME still releases volume`() = runTest {
        val c = FakeController(KioskResult.Ok); val h = FakeHome()
        val store = InMemoryKioskStateStore()
        store.save(KioskApplyPayload(features = KioskFeaturesDto(lockVolume = true)))
        KioskApplier(c, store, h, home) { true }.exit(handBackHome = false, force = true)
        assertTrue(c.exited)
        assertEquals(listOf(false), c.volume)
        assertNull(store.load())
        assertTrue("the agent stays HOME", h.log.isEmpty())
    }

    @Test fun `a non-forced failed exit leaves state and volume alone`() = runTest {
        val c = FakeController(KioskResult.Ok, exitResult = KioskResult.Failed("dpm"))
        val store = InMemoryKioskStateStore()
        store.save(KioskApplyPayload(features = KioskFeaturesDto(lockVolume = true)))
        assertEquals(KioskResult.Failed("dpm"), KioskApplier(c, store, FakeHome(), home) { true }.exit())
        assertTrue(c.volume.isEmpty())
        assertNotNull(store.load())
    }

    @Test fun `agent start clears a stale volume lock unless the persisted kiosk asks for it`() = runTest {
        val c = FakeController(KioskResult.Ok); val store = InMemoryKioskStateStore()
        KioskApplier(c, store, FakeHome(), home) { false }.releaseStaleVolumeLock()
        assertEquals("not in kiosk -> released", listOf(false), c.volume)

        store.save(KioskApplyPayload(features = KioskFeaturesDto(lockButtons = true)))
        KioskApplier(c, store, FakeHome(), home) { true }.releaseStaleVolumeLock()
        assertEquals("kiosk without lockVolume -> released", listOf(false, false), c.volume)

        store.save(KioskApplyPayload(features = KioskFeaturesDto(lockVolume = true)))
        KioskApplier(c, store, FakeHome(), home) { true }.releaseStaleVolumeLock()
        assertEquals("kiosk with lockVolume -> left locked", listOf(false, false), c.volume)
    }

    /** Delegates to [InMemoryKioskStateStore]; the first load() reads, then holds that value until [gate] completes. */
    private class GatedFirstLoadStore(
        private val inner: KioskStateStore = InMemoryKioskStateStore(),
    ) : KioskStateStore by inner {
        val gate = CompletableDeferred<Unit>()
        private var loads = 0
        override suspend fun load(): KioskApplyPayload? {
            val v = inner.load()
            if (loads++ == 0) gate.await()
            return v
        }
    }

    @Test fun `stale volume release cannot wipe the lock a concurrent kiosk enter sets`() = runTest {
        val c = FakeController(KioskResult.Ok); val store = GatedFirstLoadStore()
        val a = KioskApplier(c, store, FakeHome(), home) { false }
        // Boot-time release reads "no kiosk" and is held there; a kiosk.enter asking for the volume lock arrives.
        launch { a.releaseStaleVolumeLock() }
        advanceUntilIdle()
        launch { a.enter(KioskApplyPayload(features = KioskFeaturesDto(lockVolume = true))) }
        advanceUntilIdle()
        store.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("the enter's lock is applied last", true, c.volume.last())
        assertEquals(true, store.load()?.features?.lockVolume)
    }

    @Test fun `exit clears store and returns to oem home`() = runTest {
        val h = FakeHome(); val store = InMemoryKioskStateStore(); store.save(KioskApplyPayload())
        val a = KioskApplier(FakeController(KioskResult.Ok), store, h, home) { false }
        assertTrue(a.isPersisted())
        assertEquals(KioskResult.Ok, a.exit())
        assertFalse(a.isPersisted())
        assertEquals(listOf("claim:false", "oem"), h.log)
    }
}
