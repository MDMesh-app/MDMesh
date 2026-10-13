package com.mdmesh.core.kiosk

import android.content.ComponentName
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskFeaturesDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KioskApplierTest {
    private class FakeController(
        private val enterResult: KioskResult,
        private val exitResult: KioskResult = KioskResult.Ok,
        var volumeResult: KioskResult = KioskResult.Ok,
    ) : KioskController {
        var entered: List<String>? = null; var exited = false
        /** Every setVolumeLocked call, in order. */
        val volume = mutableListOf<Boolean>()
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult { entered = allowedPackages; return enterResult }
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

    @Test fun `crash-loop bail-out exit releases volume but keeps the agent as HOME`() = runTest {
        val c = FakeController(KioskResult.Ok); val h = FakeHome()
        val store = InMemoryKioskStateStore()
        store.save(KioskApplyPayload(features = KioskFeaturesDto(lockVolume = true)))
        KioskApplier(c, store, h, home) { true }.exit(handBackHome = false, force = true)
        assertTrue(c.exited)
        assertEquals(listOf(false), c.volume)
        assertNull(store.load())
        assertTrue("recovery screen stays HOME", h.log.isEmpty())
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

    @Test fun `exit clears store and returns to oem home`() = runTest {
        val h = FakeHome(); val store = InMemoryKioskStateStore(); store.save(KioskApplyPayload())
        val a = KioskApplier(FakeController(KioskResult.Ok), store, h, home) { false }
        assertTrue(a.isPersisted())
        assertEquals(KioskResult.Ok, a.exit())
        assertFalse(a.isPersisted())
        assertEquals(listOf("claim:false", "oem"), h.log)
    }
}
