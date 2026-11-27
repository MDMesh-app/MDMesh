package com.mdmesh.core.kiosk

import android.Manifest.permission.CAMERA
import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_PROMPT
import android.content.ComponentName
import android.os.UserManager
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KioskApplier] drives the [KioskGuard]: armed before every lock, disarmed only once lock task has ended. */
class KioskApplierGuardTest {
    private val home = ComponentName(AGENT, "$AGENT.KioskHomeAlias")
    private val a = KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a"))
    private val b = KioskApplyPayload(mode = "single", pinPackage = "com.b", allowedPackages = listOf("com.b"))

    private val d = FakeGuardDevice(
        apps = mutableMapOf("com.a" to listOf(CAMERA), "com.b" to listOf(CAMERA)),
    )
    private val records = LoggedRecords(d)
    private val store = InMemoryKioskStateStore()
    private var locked = false
    /** Times the device-wide policy went back to asking while lock task was on: must stay 0. */
    private var askedWhileLocked = 0

    init {
        d.onPolicy = { if (it == PERMISSION_POLICY_PROMPT && locked) askedWhileLocked++ }
    }

    private fun controller(exit: KioskResult = KioskResult.Ok, enter: KioskResult = KioskResult.Ok) =
        FakeController(enter, exit).apply {
            beforeEnter = { d.log += "lock:$it" }
            onExit = { d.log += "exit" }
        }

    private fun applier(c: FakeController, journal: ExitJournal = ExitJournal.None, kioskOn: Boolean = true) =
        KioskApplier(
            c, store, FakeHome(), home, journal = journal, desiredKioskOn = { kioskOn },
            guard = KioskGuard(d, records, AGENT) { locked },
        ) { locked }

    private fun armed() =
        d.policy == PERMISSION_POLICY_AUTO_GRANT && UserManager.DISALLOW_APPS_CONTROL in d.restrictions

    @Test fun `the guard is armed before the kiosk is locked`() = runTest {
        assertEquals(KioskResult.Ok, applier(controller()).enter(a))
        assertTrue(d.log.indexOf("policy:$PERMISSION_POLICY_AUTO_GRANT") < d.log.indexOf("lock:[com.a]"))
        assertTrue(d.log.indexOf("uninstallBlocked:com.a:true") < d.log.indexOf("lock:[com.a]"))
        assertTrue(armed())
        assertTrue(UserManager.DISALLOW_CREATE_WINDOWS in d.restrictions)
    }

    @Test fun `a guard that fails in part still locks the kiosk, and is reported apart from it`() = runTest {
        d.ignorePolicy = true
        val c = controller()
        val app = applier(c)
        assertEquals("the kiosk is up", KioskResult.Ok, app.enter(a))
        assertTrue("the guard's own result", app.lastGuardResult() is KioskResult.Failed)
        assertEquals(listOf(listOf("com.a")), c.enters)
        assertEquals("com.a", store.load()?.pinPackage)
        locked = true
        assertEquals("the same on a re-assert", KioskResult.Ok, app.enter(a))
        assertTrue(app.lastGuardResult() is KioskResult.Failed)
    }

    @Test fun `a guard first armed on a locked device starts the pinned app afresh, once`() = runTest {
        store.save(a) // in kiosk, from an agent without a guard
        locked = true
        val h = FakeHome()
        val guard = KioskGuard(d, records, AGENT) { locked }
        val app = KioskApplier(controller(), store, h, home, guard = guard) { locked }
        app.reconcileGuard()
        assertEquals(listOf("cleared:com.a"), h.log)
        app.reconcileGuard()
        app.enter(a)
        assertEquals("only at the first arm", listOf("cleared:com.a"), h.log)
    }

    @Test fun `a guard first armed before the lock starts nothing`() = runTest {
        val h = FakeHome()
        KioskApplier(controller(), store, h, home, guard = KioskGuard(d, records, AGENT) { locked }) { locked }.enter(a)
        assertFalse(h.log.any { it.startsWith("cleared:") })
    }

    @Test fun `an exit disarms after lock task has ended`() = runTest {
        val app = applier(controller())
        app.enter(a)
        locked = true
        assertEquals(KioskResult.Ok, app.exitAfterUnlock())
        val restored = d.log.lastIndexOf("policy:$PERMISSION_POLICY_PROMPT")
        assertTrue("disarmed after the controller's exit", d.log.indexOf("exit") < restored)
        assertFalse(armed())
        assertEquals(PERMISSION_POLICY_PROMPT, d.policy)
        assertTrue(d.uninstallBlocked.isEmpty())
        assertEquals(0, askedWhileLocked)
    }

    /** Exits with lock task ending as the controller's exit runs, as it does on a device. */
    private suspend fun KioskApplier.exitAfterUnlock(force: Boolean = false): KioskResult =
        exit(force = force, beforeSteps = { locked = false })

    @Test fun `a refused exit never disarms`() = runTest {
        val c = controller(exit = KioskResult.Failed("dpm"))
        val app = applier(c)
        app.enter(a)
        locked = true
        c.exitKeepsAllowlist = true
        assertTrue(app.exit(force = false) is KioskResult.Failed)
        assertTrue(armed())
        assertEquals(setOf("com.a"), d.uninstallBlocked)
    }

    @Test fun `a forced exit with lock task still on keeps the guard until lock task ends`() = runTest {
        val c = controller(exit = KioskResult.Failed("dpm"))
        val app = applier(c)
        app.enter(a)
        locked = true
        c.exitKeepsAllowlist = true
        app.exit(force = true) // the person at the device must get out, but lock task stayed on
        assertEquals("the kiosk's state is cleared", null, store.load())
        assertTrue("still armed while locked", armed())
        assertEquals(setOf("com.a"), d.uninstallBlocked)
        // Lock task ends later (the lock-task callback): only then is the guard disarmed.
        locked = false
        c.allowlist = emptyList()
        assertEquals(KioskResult.Ok, app.reconcileGuard())
        assertFalse(armed())
        assertEquals(0, askedWhileLocked)
    }

    @Test fun `a forced exit whose controller call worked but whose task is still locked waits too`() = runTest {
        val app = applier(controller())
        app.enter(a)
        locked = true
        app.exit(force = true)
        assertTrue(armed())
        locked = false
        app.reconcileGuard()
        assertFalse(armed())
        assertEquals(0, askedWhileLocked)
    }

    @Test fun `a switch protects both kiosks until the new one is locked, then only it`() = runTest {
        var atLock: Set<String>? = null
        val c = controller()
        val app = applier(c)
        app.enter(a)
        locked = true
        c.beforeEnter = { atLock = d.uninstallBlocked.toSet() }
        app.enter(b)
        assertEquals(setOf("com.a", "com.b"), atLock)
        assertEquals(setOf("com.b"), d.uninstallBlocked)
        assertTrue(armed())
    }

    @Test fun `a failed switch keeps the old kiosk, and only it, protected`() = runTest {
        val c = controller()
        val app = applier(c)
        app.enter(a)
        locked = true
        c.failFor = listOf("com.b")
        assertTrue(app.enter(b) is KioskResult.Failed)
        assertEquals(setOf("com.a"), d.uninstallBlocked)
        assertTrue(armed())
    }

    @Test fun `a failed first entry leaves nothing armed`() = runTest {
        val app = applier(controller(enter = KioskResult.Failed("dpm")))
        assertTrue(app.enter(a) is KioskResult.Failed)
        assertFalse(armed())
        assertEquals(PERMISSION_POLICY_PROMPT, d.policy)
        assertTrue(d.uninstallBlocked.isEmpty())
    }

    @Test fun `the same kiosk sent again puts back a policy changed out of band`() = runTest {
        val app = applier(controller())
        app.enter(a)
        locked = true
        d.policy = PERMISSION_POLICY_PROMPT // reset by something else
        d.restrictions -= UserManager.DISALLOW_APPS_CONTROL
        assertEquals(KioskResult.Ok, app.enter(a))
        assertTrue(armed())
    }

    @Test fun `an agent updated in kiosk arms at its first start, keeping the policy it finds as the prior one`() =
        runTest {
            store.save(a)
            locked = true
            assertEquals(KioskResult.Ok, applier(controller()).reconcileGuard())
            assertTrue(armed())
            assertEquals(PERMISSION_POLICY_PROMPT, records.record?.priorPolicy)
            assertEquals(setOf("com.a"), d.uninstallBlocked)
        }

    @Test fun `lock task on with no kiosk saved still arms, for the allowlisted apps`() = runTest {
        val c = controller().apply { allowlist = listOf("com.b", AGENT) }
        locked = true
        applier(c).reconcileGuard()
        assertTrue(armed())
        assertEquals(setOf("com.b"), records.record?.packages)
    }

    @Test fun `lock task on is enough to arm, even with no kiosk saved and no allowlist to read`() = runTest {
        locked = true
        assertEquals(KioskResult.Ok, applier(controller()).reconcileGuard())
        assertTrue(armed())
    }

    @Test fun `a guard left armed with no kiosk and lock task off is disarmed at start`() = runTest {
        KioskGuard(d, records, AGENT) { locked }.arm(setOf("com.a"))
        assertEquals(KioskResult.Ok, applier(controller()).reconcileGuard())
        assertFalse(armed())
        assertEquals(PERMISSION_POLICY_PROMPT, d.policy)
    }

    @Test fun `no guard step runs while a wipe holds the kiosk lock`() = runTest {
        store.save(a)
        locked = true
        val app = applier(controller())
        val wipeCalled = CompletableDeferred<Unit>()
        var blockedDuringWipe = false
        val wipe = launch {
            app.exclusive {
                wipeCalled.await() // the lift and the wipe call happen in here
                blockedDuringWipe = UserManager.DISALLOW_FACTORY_RESET in d.restrictions
            }
        }
        val reconcile = launch { app.reconcileGuard() } // the lock-task callback's worker, say
        advanceUntilIdle()
        assertFalse("the reconcile waits", armed())
        wipeCalled.complete(Unit)
        wipe.join()
        reconcile.join()
        assertFalse(blockedDuringWipe)
        assertTrue("and runs after", armed())
    }

    @Test fun `a kiosk left unlocked without an exit is shown again, and only then`() = runTest {
        val h = FakeHome()
        fun app() = KioskApplier(
            controller(), store, h, home, guard = KioskGuard(d, records, AGENT) { locked },
        ) { locked }
        assertFalse("no kiosk saved", app().returnToKiosk())
        store.save(a)
        locked = true
        assertFalse("still locked", app().returnToKiosk())
        assertTrue(h.log.isEmpty())
        locked = false // an agent update ended lock task
        assertTrue(app().returnToKiosk())
        assertEquals("the pinned app afresh, then HOME", listOf("claim:true", "cleared:com.a", "launcher"), h.log)
    }

    @Test fun `an allowlist that can't be read keeps an armed guard armed`() = runTest {
        KioskGuard(d, records, AGENT) { locked }.arm(setOf("com.a"))
        val c = controller().apply { allowlistUnreadable = true }
        assertEquals(KioskResult.Ok, applier(c).reconcileGuard())
        assertTrue(armed())
    }

    @Test fun `with neither a saved kiosk nor a readable allowlist, no kiosk app is let go until one can be read`() =
        runTest {
            val c = controller()
            val app = applier(c)
            app.enter(a)
            locked = true
            store.save(null) // storage lost
            c.allowlistUnreadable = true
            assertEquals(KioskResult.Ok, app.reconcileGuard())
            assertEquals(setOf("com.a"), d.uninstallBlocked)
            assertTrue(app.protectsApp("com.a"))
            // Readable again, still locked: settled on what is allowlisted now.
            c.allowlistUnreadable = false
            c.allowlist = listOf("com.b", AGENT)
            assertEquals(KioskResult.Ok, app.reconcileGuard())
            assertEquals(setOf("com.b"), d.uninstallBlocked)
        }

    @Test fun `a saved kiosk is enough to settle on, the allowlist unreadable`() = runTest {
        KioskGuard(d, records, AGENT) { locked }.arm(setOf("com.a", "com.b"))
        store.save(b)
        locked = true
        val c = controller().apply { allowlistUnreadable = true }
        assertEquals(KioskResult.Ok, applier(c).reconcileGuard())
        assertEquals(setOf("com.b"), d.uninstallBlocked)
    }

    @Test fun `no kiosk app stays blocked outside kiosk once the allowlist can be read`() = runTest {
        for (restarted in listOf(false, true)) {
            val c = controller()
            val app = applier(c)
            app.enter(a)
            locked = true
            store.save(null) // storage lost
            c.allowlistUnreadable = true
            app.reconcileGuard()
            locked = false // lock task ended, the allowlist still unreadable
            app.reconcileGuard()
            assertEquals("the guard stays as it is", setOf("com.a"), d.uninstallBlocked)
            c.allowlistUnreadable = false
            c.allowlist = emptyList()
            val next = if (restarted) applier(c) else app
            assertEquals(KioskResult.Ok, next.reconcileGuard())
            assertTrue(d.uninstallBlocked.isEmpty())
            assertFalse(armed())
            assertEquals(null, records.record)
        }
    }

    @Test fun `an allowlist that can't be read never finishes a disarm a past run left partway`() = runTest {
        KioskGuard(d, records, AGENT) { locked }.arm(setOf("com.a"))
        records.record = records.record!!.copy(releasing = true)
        val c = controller().apply { allowlistUnreadable = true }
        assertEquals(KioskResult.Ok, applier(c).reconcileGuard())
        assertTrue(armed())
        assertTrue("its record stands", records.record != null)
    }

    @Test fun `a record that can't be read at first never starts the pinned app afresh`() = runTest {
        KioskGuard(d, records, AGENT) { locked }.arm(setOf("com.a"))
        store.save(a)
        locked = true
        var faults = 1
        val flaky = object : GuardRecordStore by records {
            override fun load() = if (faults-- > 0) throw java.io.IOException("unreadable") else records.load()
        }
        val h = FakeHome()
        val app = KioskApplier(controller(), store, h, home, guard = KioskGuard(d, flaky, AGENT) { locked }) { locked }
        assertEquals(KioskResult.Ok, app.reconcileGuard())
        assertTrue(armed())
        assertEquals("already armed: nothing is relaunched", emptyList<String>(), h.log)
    }

    @Test fun `an exit after the record was lost still unblocks the kiosk's apps`() = runTest {
        val c = controller()
        applier(c).enter(a)
        records.record = null // storage lost
        d.unlistable = true // and the apps can't be listed either: only the exiting kiosk names them
        locked = true
        applier(c).exitAfterUnlock()
        assertTrue(d.uninstallBlocked.isEmpty())
    }

    @Test fun `a record lost outside kiosk leaves no kiosk restriction on the device`() = runTest {
        KioskGuard(d, records, AGENT) { locked }.arm(setOf("com.a"))
        records.record = null // storage lost
        assertEquals(KioskResult.Ok, applier(controller()).reconcileGuard())
        assertFalse(UserManager.DISALLOW_APPS_CONTROL in d.restrictions)
        assertFalse(UserManager.DISALLOW_CREATE_WINDOWS in d.restrictions)
        assertTrue(d.uninstallBlocked.isEmpty())
    }

    @Test fun `an exit cut off and finished at the next start disarms, one rolled back stays armed`() = runTest {
        for (kioskOn in listOf(false, true)) {
            val c = controller()
            val journal = MemJournal()
            store.save(null)
            records.record = null
            d.restrictions.clear(); d.uninstallBlocked.clear(); d.policy = PERMISSION_POLICY_PROMPT
            applier(c, journal, kioskOn).enter(a)
            // A process death right after the journal entry was written: the next start settles it.
            journal.saved = ExitJournal.Pending(true, false, ExitJournal.fingerprint(a))
            val next = applier(c, journal, kioskOn)
            next.resumeInterruptedExit()
            assertEquals("kioskOn=$kioskOn", kioskOn, armed())
        }
    }

    private companion object {
        const val AGENT = "com.mdmesh.agent"
    }
}
