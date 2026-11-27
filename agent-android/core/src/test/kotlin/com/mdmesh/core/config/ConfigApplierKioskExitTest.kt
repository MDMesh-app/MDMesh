package com.mdmesh.core.config

import android.Manifest.permission.CAMERA
import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_PROMPT
import android.content.ComponentName
import android.os.UserManager
import com.mdmesh.core.kiosk.FakeController
import com.mdmesh.core.kiosk.FakeGuardDevice
import com.mdmesh.core.kiosk.FakeHome
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskGuard
import com.mdmesh.core.kiosk.LoggedRecords
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.core.store.reportedRevision
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.ProbeError
import com.mdmesh.policy.ResolvedToggles
import com.mdmesh.policy.factoryreset.FactoryResetPolicy
import com.mdmesh.policy.factoryreset.KioskHeldFactoryResetPolicy
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A configuration with kiosk off, on a device with an armed [KioskGuard]: what the exit puts back and reports. */
class ConfigApplierKioskExitTest {

    private class Device(io: CoroutineDispatcher) {
        val config = InMemoryConfigStateStore()
        val saved = InMemoryKioskStateStore()
        val d = FakeGuardDevice(apps = mutableMapOf("com.a" to listOf(CAMERA), "com.b" to listOf(CAMERA)))
        val records = LoggedRecords(d)
        val home = FakeHome()
        var locked = false
        /** Lock task ends with the exit call; false: only later, seen by the lock-task callback. */
        var endsAtOnce = true
        val controller = FakeController(KioskResult.Ok).apply { onExit = { if (endsAtOnce) locked = false } }
        val agent = Agent(this, io)

        fun lockTaskEnds() { locked = false }
    }

    /** One process of the agent on the device [on]. */
    private class Agent(on: Device, io: CoroutineDispatcher = Dispatchers.Unconfined) {
        private val config = on.config
        private val guard = KioskGuard(
            on.d, on.records, "com.mdmesh.agent",
            factoryResetSetting = { (config.delivered ?: config.load())?.policies?.get(FACTORY_RESET) },
        ) { on.locked }
        val kiosk = KioskApplier(
            on.controller, on.saved, on.home, ComponentName("com.mdmesh.agent", "b"), io = io, guard = guard,
        ) { on.locked }
        private val inner = object : FactoryResetPolicy {
            override val capabilityKey = FACTORY_RESET
            override fun isSupported() = true
            override fun setEnabled(enabled: Boolean): PolicyOutcome {
                on.d.setRestriction(UserManager.DISALLOW_FACTORY_RESET, !enabled)
                return PolicyOutcome.Applied
            }
        }
        var toggles = ResolvedToggles(mapOf(FACTORY_RESET to KioskHeldFactoryResetPolicy(inner, guard)))
        val applier = ConfigApplier({ toggles }, kiosk, {}, config, setPowerMode = {})
    }

    private fun kiosk(app: String) =
        KioskApplyPayload(mode = "single", pinPackage = "com.$app", allowedPackages = listOf("com.$app"))

    private fun doc(revision: String, factoryReset: Boolean? = null, kiosk: KioskApplyPayload? = null) =
        ConfigApplyPayload(
            revision = revision, kiosk = kiosk,
            policies = listOfNotNull(factoryReset?.let { FACTORY_RESET to it }).toMap(),
        )

    private suspend fun inConfiguredKiosk(
        factoryReset: Boolean? = null,
        io: CoroutineDispatcher = Dispatchers.Unconfined,
    ): Device {
        val device = Device(io)
        device.agent.applier.apply(doc("s1", factoryReset, kiosk("a")))
        device.locked = true
        return device
    }

    private fun Device.assertDisarmed(why: String) {
        assertEquals(why, PERMISSION_POLICY_PROMPT, d.policy)
        assertTrue(why, d.restrictions.isEmpty())
        assertTrue(why, d.uninstallBlocked.isEmpty())
        assertTrue("$why: the grants made in kiosk can be revoked", d.fixed.isEmpty())
        assertNull(why, records.record)
    }

    @Test fun `the configuration's kiosk-off document ends a custom kiosk and disarms the guard in full`() = runTest {
        for (atOnce in listOf(true, false)) {
            val device = inConfiguredKiosk()
            device.agent.kiosk.enter(kiosk("b"))
            device.d.requestUnderPolicy("com.b", CAMERA)
            device.endsAtOnce = atOnce
            assertEquals("applied", device.agent.applier.apply(doc("d1")).outcomes["kiosk"])
            assertNull(device.saved.load())
            if (!atOnce) {
                assertEquals("armed until lock task ends", PERMISSION_POLICY_AUTO_GRANT, device.d.policy)
                assertTrue(UserManager.DISALLOW_APPS_CONTROL in device.d.restrictions)
                assertTrue(UserManager.DISALLOW_FACTORY_RESET in device.d.restrictions)
                device.lockTaskEnds()
                device.agent.kiosk.reconcileGuard()
            }
            device.assertDisarmed("at once=$atOnce")
        }
    }

    @Test fun `a kiosk exit an agent of 0_5_1 left owed is retried, and disarms the guard`() = runTest {
        val device = Device(Dispatchers.Unconfined)
        device.agent.kiosk.enter(kiosk("a"))
        device.locked = true
        // 0.5.1's record: neither document had a kiosk any more, the exit failed.
        device.config.save(doc("s0"))
        device.config.delivered = doc("d1")
        device.config.unreleased = setOf("kiosk")
        device.controller.exitResult = KioskResult.Failed("lock task busy")
        device.controller.exitKeepsAllowlist = true
        device.endsAtOnce = false
        device.agent.applier.apply(doc("d1"))
        assertNotNull("failed again", device.saved.load())
        assertEquals(setOf("kiosk"), device.config.unreleased)
        device.controller.exitResult = KioskResult.Ok
        device.controller.exitKeepsAllowlist = false
        device.endsAtOnce = true
        device.agent.applier.apply(doc("d1"))
        assertNull(device.saved.load())
        assertTrue(device.config.unreleased.isEmpty())
        assertEquals("d1", device.config.reportedRevision())
        device.assertDisarmed("retried")
    }

    @Test fun `a factory reset setting the guard can't save never leaves the configuration's document unsaved`() =
        runTest {
            for (allow in listOf(true, false)) for (readable in listOf(true, false)) {
                val why = "allow=$allow readable=$readable"
                val device = inConfiguredKiosk(factoryReset = allow)
                if (readable) device.records.writable = false else device.records.unreadable = true
                val agent = if (readable) device.agent else Agent(device) // a process that never read the record
                val off = doc("d1", factoryReset = allow)
                agent.applier.apply(off)
                assertEquals(why, "d1", device.config.reportedRevision())
                device.records.writable = true
                device.records.unreadable = false
                agent.kiosk.reconcileGuard()
                val blocked = UserManager.DISALLOW_FACTORY_RESET in device.d.restrictions
                assertEquals("$why: the configuration's setting wasn't lost", !allow, blocked)
                // So a kiosk entered since by kiosk.enter isn't taken for the one that document ends.
                agent.kiosk.enter(kiosk("b"))
                device.locked = true
                agent.applier.reapplyPersisted()
                agent.applier.apply(off)
                assertEquals(why, kiosk("b"), device.saved.load())
            }
        }

    @Test fun `an Allow on the document that ends kiosk is reported applied`() = runTest {
        val device = inConfiguredKiosk(factoryReset = true)
        val r = device.agent.applier.apply(doc("d1", factoryReset = true))
        assertEquals(ConfigOutcome.APPLIED, r.outcomes["policies.$FACTORY_RESET"])
        device.assertDisarmed("ended")
    }

    @Test fun `an Allow on the document that ends kiosk stays deferred while lock task still runs`() = runTest {
        val device = inConfiguredKiosk(factoryReset = true)
        device.endsAtOnce = false
        val r = device.agent.applier.apply(doc("d1", factoryReset = true))
        assertTrue(r.outcomes["policies.$FACTORY_RESET"].orEmpty().startsWith("deferred"))
        assertTrue(UserManager.DISALLOW_FACTORY_RESET in device.d.restrictions)
    }

    @Test fun `a factory reset whose check threw stays failed on the document that ends kiosk`() = runTest {
        val device = inConfiguredKiosk(factoryReset = true)
        val error = ProbeError(IllegalStateException("binder died"), kept = null)
        device.agent.toggles = ResolvedToggles(emptyMap(), mapOf(FACTORY_RESET to error))
        val r = device.agent.applier.apply(doc("d1", factoryReset = true))
        assertEquals(ConfigOutcome.failed(error.reason), r.outcomes["policies.$FACTORY_RESET"])
        assertEquals("not saved", "s1", device.config.load()?.revision)
    }

    @Test fun `a kiosk-off document cancelled while the guard is disarmed still hands HOME back`() = runTest {
        val device = inConfiguredKiosk(io = StandardTestDispatcher(testScheduler))
        device.home.log.clear()
        var job: Job? = null
        device.d.beforeChange = { if (it == "policy:$PERMISSION_POLICY_PROMPT") job!!.cancel() }
        job = launch { device.agent.applier.apply(doc("d1")) }
        job.join()
        assertTrue("cancelled", job.isCancelled)
        assertNull(device.saved.load())
        assertEquals(listOf("claim:false", "oem"), device.home.log)
        device.assertDisarmed("cancelled")
    }

    private companion object {
        const val FACTORY_RESET = FactoryResetPolicy.CAPABILITY_KEY
    }
}
