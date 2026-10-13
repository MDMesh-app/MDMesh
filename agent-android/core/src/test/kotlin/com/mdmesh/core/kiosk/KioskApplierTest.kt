package com.mdmesh.core.kiosk

import android.content.ComponentName
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KioskApplierTest {
    private class FakeController(private val enterResult: KioskResult, private val exitResult: KioskResult = KioskResult.Ok) : KioskController {
        var entered: List<String>? = null; var exited = false
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult { entered = allowedPackages; return enterResult }
        override fun exit(): KioskResult { exited = true; return exitResult }
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
    @Test fun `exit clears store and returns to oem home`() = runTest {
        val h = FakeHome(); val store = InMemoryKioskStateStore(); store.save(KioskApplyPayload())
        val a = KioskApplier(FakeController(KioskResult.Ok), store, h, home) { false }
        assertTrue(a.isPersisted())
        assertEquals(KioskResult.Ok, a.exit())
        assertFalse(a.isPersisted())
        assertEquals(listOf("claim:false", "oem"), h.log)
    }
}
