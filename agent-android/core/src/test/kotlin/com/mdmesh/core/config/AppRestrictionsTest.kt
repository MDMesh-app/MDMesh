package com.mdmesh.core.config

import android.content.ComponentName
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskHomeSwitch
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.kiosk.StubKioskController
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.UserAppPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppRestrictionsTest {
    private class Store : AppRestrictionStore {
        var state = AppRestrictionState()
        var failWrites = false
        override fun load() = state
        override fun save(state: AppRestrictionState) {
            check(!failWrites) { "disk full" }
            this.state = state
        }
    }
    private class Backend : AppRestrictionBackend {
        var supported = true
        var unknown = false
        var failRestore = false
        var failHide = false
        val packages = mutableSetOf(
            "com.android.vending", "com.sec.android.app.samsungapps", "com.acme.app", "com.acme.other",
        )
        val hidden = mutableSetOf<String>()
        val blocked = mutableSetOf<String>()
        override fun isSupported() = supported
        override fun installedPackages() = packages.toSet()
        override fun isProtectedPackage(pkg: String) =
            pkg == "com.mdmesh.agent" || pkg == "com.android.packageinstaller"
        override fun unknownSourcesBlocked() = unknown
        override fun setUnknownSourcesBlocked(blocked: Boolean) { unknown = blocked }
        override fun isHidden(pkg: String) = pkg in hidden
        override fun setHidden(pkg: String, hidden: Boolean) {
            check(!failHide) { "OEM refused hiding" }
            if (hidden) this.hidden += pkg else this.hidden -= pkg
        }
        override fun isUninstallBlocked(pkg: String) = pkg in blocked
        override fun setUninstallBlocked(pkg: String, blocked: Boolean) {
            check(!failRestore || !blocked) { "OEM refused restoration" }
            if (blocked) this.blocked += pkg else this.blocked -= pkg
        }
    }
    private val installBlock = mapOf(UserAppPolicy.INSTALL to false)
    private val uninstallBlock = mapOf(UserAppPolicy.UNINSTALL to false)

    @Test fun `config apply routes app keys and store list and reports each outcome`() = runTest {
        val b = Backend(); val s = Store(); val restrictions = AppRestrictions(b, s)
        val home = object : KioskHomeSwitch {
            override fun setClaimEnabled(enabled: Boolean) = Unit
            override fun showLauncher() = Unit
            override fun showOemHome() = Unit
        }
        val applier = ConfigApplier(emptyMap(), KioskApplier(StubKioskController(),
            InMemoryKioskStateStore(), home, ComponentName("a", "b")), {}, InMemoryConfigStateStore(), restrictions)
        val result = applier.apply(ConfigApplyPayload(revision = "r1", configurationId = 1,
            policies = installBlock + uninstallBlock, blockedAppStores = listOf("com.acme.other")))
        assertEquals(ConfigOutcome.APPLIED, result.outcomes["policies.userAppInstall"])
        assertEquals(ConfigOutcome.APPLIED, result.outcomes["policies.userAppUninstall"])
        assertEquals(setOf("com.acme.other"), b.hidden)
        applier.apply(ConfigApplyPayload(revision = "auto", configurationId = 1))
        b.hidden.clear()
        applier.reapplyPersisted()
        assertEquals(setOf("com.acme.other"), b.hidden)
    }

    @Test fun `user installs blocked and MDM install stays available`() = runTest {
        val b = Backend(); val s = Store(); val r = AppRestrictions(b, s)
        assertEquals(ConfigOutcome.APPLIED, r.apply(installBlock, null)[UserAppPolicy.INSTALL])
        assertTrue(b.unknown)
        assertEquals(UserAppPolicy.DEFAULT_STORES, b.hidden)
        assertEquals("installed", r.managedInstall("com.acme.new") { b.packages += "com.acme.new"; "installed" })
        assertTrue(b.unknown)
        assertEquals(UserAppPolicy.DEFAULT_STORES, b.hidden)
    }

    @Test fun `Auto and restart retain policies and Off explicitly restores`() = runTest {
        val b = Backend(); val s = Store(); val r = AppRestrictions(b, s)
        r.apply(installBlock + uninstallBlock, null)
        r.apply(emptyMap(), emptyList())
        val restarted = AppRestrictions(b, s)
        b.unknown = false; b.hidden.clear(); b.blocked.clear()
        restarted.reconcile()
        assertTrue(b.unknown); assertEquals(UserAppPolicy.DEFAULT_STORES, b.hidden)
        assertEquals(b.packages, b.blocked)
        restarted.apply(mapOf(UserAppPolicy.INSTALL to true, UserAppPolicy.UNINSTALL to true), null)
        assertFalse(b.unknown); assertTrue(b.hidden.isEmpty()); assertTrue(b.blocked.isEmpty())
    }

    @Test fun `store list changes restore only agent hidden stores`() = runTest {
        val b = Backend(); val s = Store(); b.hidden += "com.sec.android.app.samsungapps"
        val r = AppRestrictions(b, s)
        r.apply(installBlock, null)
        r.apply(installBlock, listOf("com.acme.other", "com.missing.store"))
        assertEquals(setOf("com.sec.android.app.samsungapps", "com.acme.other"), b.hidden)
        r.apply(mapOf(UserAppPolicy.INSTALL to true), null)
        assertEquals(setOf("com.sec.android.app.samsungapps"), b.hidden)
    }

    @Test fun `preexisting restrictions are preserved on Off`() = runTest {
        val b = Backend(); val s = Store(); b.unknown = true; b.blocked += "com.acme.other"
        val r = AppRestrictions(b, s)
        r.apply(installBlock + uninstallBlock, null)
        r.apply(mapOf(UserAppPolicy.INSTALL to true, UserAppPolicy.UNINSTALL to true), null)
        assertTrue(b.unknown); assertEquals(setOf("com.acme.other"), b.blocked)
    }

    @Test fun `empty list explicitly hides no stores and invalid system lists fail safely`() = runTest {
        val b = Backend(); val s = Store(); val r = AppRestrictions(b, s)
        r.apply(installBlock, emptyList())
        assertTrue(b.unknown); assertTrue(b.hidden.isEmpty())
        for (pkg in listOf("com.mdmesh.agent", "com.android.packageinstaller", "bad package")) {
            assertTrue(ConfigOutcome.isFailed(r.apply(installBlock, listOf(pkg)).getValue(UserAppPolicy.INSTALL)))
        }
        assertTrue(s.state.stores.isEmpty())
    }

    @Test fun `new packages are protected after install and package reconciliation`() = runTest {
        val b = Backend(); val s = Store(); val r = AppRestrictions(b, s)
        r.apply(uninstallBlock, null)
        r.managedInstall("com.acme.new") { b.packages += "com.acme.new" }
        assertTrue("com.acme.new" in b.blocked)
        b.packages += "com.acme.external"
        r.reconcile()
        assertTrue("com.acme.external" in b.blocked)
    }

    @Test fun `targeted removal leaves other apps protected and drops removed package journal`() = runTest {
        val b = Backend(); val s = Store(); val r = AppRestrictions(b, s)
        r.apply(uninstallBlock, null)
        r.managedRemoval("com.acme.app") {
            assertFalse("com.acme.app" in b.blocked)
            assertTrue("com.acme.other" in b.blocked)
            assertEquals("com.acme.app", s.state.pendingRemoval)
            b.packages -= "com.acme.app"
        }
        assertNull(s.state.pendingRemoval)
        assertFalse("com.acme.app" in s.state.protectedByAgent)
    }

    @Test fun `failed removal and cancellation reblock target`() = runTest {
        val b = Backend(); val s = Store(); val r = AppRestrictions(b, s)
        r.apply(uninstallBlock, null)
        try { r.managedRemoval("com.acme.app") { error("uninstall rejected") } } catch (_: IllegalStateException) { }
        assertTrue("com.acme.app" in b.blocked); assertNull(s.state.pendingRemoval)
        val started = CompletableDeferred<Unit>()
        val task = async {
            r.managedRemoval("com.acme.app") { started.complete(Unit); CompletableDeferred<Unit>().await() }
        }
        started.await(); task.cancel(); task.join()
        assertTrue("com.acme.app" in b.blocked); assertNull(s.state.pendingRemoval)
    }

    @Test fun `reapply waits for removal before touching package protection`() = runTest {
        val b = Backend(); val s = Store(); val r = AppRestrictions(b, s)
        r.apply(uninstallBlock, null)
        val release = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        val removing = async { r.managedRemoval("com.acme.app") { started.complete(Unit); release.await() } }
        started.await()
        val applying = async { r.apply(uninstallBlock, null) }
        runCurrent()
        assertFalse(applying.isCompleted); assertFalse("com.acme.app" in b.blocked)
        release.complete(Unit); removing.await(); applying.await()
        assertTrue("com.acme.app" in b.blocked)
    }

    @Test fun `cold start repairs interrupted removal even without last config`() = runTest {
        val b = Backend(); val s = Store()
        s.state = AppRestrictionState(
            uninstallBlocked = true, protectedByAgent = setOf("com.acme.app"), pendingRemoval = "com.acme.app",
        )
        b.failRestore = true
        try { AppRestrictions(b, s).reconcile() } catch (_: IllegalStateException) { }
        assertEquals("com.acme.app", s.state.pendingRemoval)
        b.failRestore = false
        AppRestrictions(b, s).reconcile()
        assertTrue("com.acme.app" in b.blocked); assertNull(s.state.pendingRemoval)
    }

    @Test fun `unsupported device never changes OS and persistence failure prevents mutations`() = runTest {
        val b = Backend(); val s = Store(); val r = AppRestrictions(b, s)
        b.supported = false
        assertEquals(ConfigOutcome.UNSUPPORTED, r.apply(installBlock, null)[UserAppPolicy.INSTALL])
        assertFalse(b.unknown); assertTrue(b.hidden.isEmpty())
        b.supported = true; s.failWrites = true
        assertTrue(ConfigOutcome.isFailed(r.apply(installBlock, null).getValue(UserAppPolicy.INSTALL)))
        assertFalse(b.unknown); assertTrue(b.hidden.isEmpty())
    }

    @Test fun `partial hide failure is recoverable and absence of policy preserves journal`() = runTest {
        val b = Backend(); val s = Store(); val r = AppRestrictions(b, s)
        b.failHide = true
        assertTrue(ConfigOutcome.isFailed(r.apply(installBlock, null).getValue(UserAppPolicy.INSTALL)))
        r.apply(emptyMap(), null)
        b.failHide = false
        AppRestrictions(b, s).reconcile()
        assertEquals(UserAppPolicy.DEFAULT_STORES, b.hidden)
    }
}
