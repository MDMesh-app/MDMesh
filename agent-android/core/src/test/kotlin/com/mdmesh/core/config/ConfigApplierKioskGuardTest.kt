package com.mdmesh.core.config

import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_PROMPT
import android.content.ComponentName
import com.mdmesh.core.kiosk.FakeController
import com.mdmesh.core.kiosk.FakeGuardDevice
import com.mdmesh.core.kiosk.FakeHome
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskGuard
import com.mdmesh.core.kiosk.LoggedRecords
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.core.testing.fixedToggles
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigAppPermissions
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.policy.AppPermissionOutcome
import com.mdmesh.policy.AppRuntimePermissions
import com.mdmesh.policy.PermissionGrantLock
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.factoryreset.FactoryResetPolicy
import com.mdmesh.policy.factoryreset.KioskHeldFactoryResetPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every `config.apply` settles the kiosk guard, so one a past exit couldn't finish is finished, or reported. */
class ConfigApplierKioskGuardTest {
    private val d = FakeGuardDevice(apps = mutableMapOf("com.a" to emptyList()))
    private val records = LoggedRecords(d)
    private val guard = KioskGuard(d, records, "com.mdmesh.agent") { false }
    private val store = InMemoryConfigStateStore()
    private val kiosk = KioskApplier(
        FakeController(KioskResult.Ok), InMemoryKioskStateStore(), FakeHome(),
        ComponentName("com.mdmesh.agent", "b"), guard = guard,
    ) { false }
    private val applier = ConfigApplier(fixedToggles(), kiosk, {}, store, setPowerMode = {})

    @Test fun `a guard left armed outside kiosk is disarmed by the next apply`() = runTest {
        guard.arm(setOf("com.a"))
        val r = applier.apply(ConfigApplyPayload(revision = "r1"))
        assertTrue(ConfigApplier.succeeded(r))
        assertFalse(guard.held())
        assertEquals(PERMISSION_POLICY_PROMPT, d.policy)
    }

    @Test fun `a guard that can't be put back is reported on its own key, deferred, and the document is saved`() =
        runTest {
            guard.arm(setOf("com.a"))
            records.writable = false
            val r = applier.apply(ConfigApplyPayload(revision = "r1"))
            assertNull(r.outcomes["kiosk"])
            assertTrue(r.outcomes[ConfigApplier.KIOSK_GUARD].orEmpty().startsWith("deferred"))
            assertEquals("r1", store.load()?.revision)
            assertTrue(store.unreleased.isEmpty())
        }

    @Test fun `a guard failure never makes a later apply exit a kiosk entered by kiosk dot enter`() = runTest {
        val d = FakeGuardDevice(apps = mutableMapOf("com.a" to emptyList())).apply { ignorePolicy = true }
        val kstore = InMemoryKioskStateStore()
        val c = FakeController(KioskResult.Ok)
        var locked = false
        val adHoc = KioskApplier(
            c, kstore, FakeHome(), ComponentName("com.mdmesh.agent", "b"),
            guard = KioskGuard(d, LoggedRecords(d), "com.mdmesh.agent") { locked },
        ) { locked }
        val config = InMemoryConfigStateStore()
        val a = ConfigApplier(fixedToggles(), adHoc, {}, config, setPowerMode = {})
        val kioskPayload = KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a"))
        adHoc.enter(kioskPayload) // a kiosk.enter command: no configuration has a kiosk
        locked = true
        a.apply(ConfigApplyPayload(revision = "r1"))
        a.apply(ConfigApplyPayload(revision = "r2"))
        assertFalse("never exited", c.exited)
        assertNotNull(kstore.load())
    }

    @Test fun `the configuration's rule writes hold the lock the kiosk guard takes`() = runTest {
        val lock = PermissionGrantLock()
        val held = mutableListOf<Boolean>()
        val permissions = object : AppRuntimePermissions {
            override fun set(packageName: String, permission: String, state: String): AppPermissionOutcome {
                held += lock.isHeldByCurrentThread()
                return AppPermissionOutcome.Applied
            }
        }
        val a = ConfigApplier(fixedToggles(), kiosk, {}, store, setPowerMode = {}, appPermissions = permissions,
            grantLock = lock)
        a.apply(ConfigApplyPayload(revision = "p1", appPermissions = listOf(
            ConfigAppPermissions("com.a", mapOf("android.permission.CAMERA" to "deny")),
        )))
        a.apply(ConfigApplyPayload(revision = "p2", appPermissions = emptyList())) // released
        assertEquals(listOf(true, true), held)
    }

    @Test fun `an Allow on the document that enters kiosk is reported deferred, not applied`() = runTest {
        val cleared = mutableListOf<Boolean>()
        val inner = object : FactoryResetPolicy {
            override val capabilityKey = FactoryResetPolicy.CAPABILITY_KEY
            override fun isSupported() = true
            override fun setEnabled(enabled: Boolean): PolicyOutcome {
                cleared += enabled
                return PolicyOutcome.Applied
            }
        }
        val toggles = fixedToggles(FactoryResetPolicy.CAPABILITY_KEY to KioskHeldFactoryResetPolicy(inner, guard))
        val a = ConfigApplier(toggles, kiosk, {}, store, setPowerMode = {})
        val r = a.apply(ConfigApplyPayload(
            revision = "k1", policies = mapOf(FactoryResetPolicy.CAPABILITY_KEY to true),
            kiosk = KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a")),
        ))
        assertTrue(r.outcomes["policies.factoryReset"].orEmpty().startsWith("deferred"))
        assertTrue("the block stands", android.os.UserManager.DISALLOW_FACTORY_RESET in d.restrictions)
    }

    @Test fun `a guard failure on a document with a kiosk is deferred on its own key, the kiosk applied`() = runTest {
        d.ignorePolicy = true
        val r = applier.apply(ConfigApplyPayload(
            revision = "k2",
            kiosk = KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a")),
        ))
        assertEquals("applied", r.outcomes["kiosk"])
        assertTrue(r.outcomes[ConfigApplier.KIOSK_GUARD].orEmpty().startsWith("deferred"))
        assertEquals("saved, not re-sent", "k2", store.load()?.revision)
    }
}
