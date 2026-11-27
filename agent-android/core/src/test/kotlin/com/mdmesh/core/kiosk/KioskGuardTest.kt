package com.mdmesh.core.kiosk

import android.Manifest
import android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
import android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY
import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_PROMPT
import android.os.UserManager
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PermissionGrantLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskGuardTest {
    private val camera = Manifest.permission.CAMERA
    private val fine = Manifest.permission.ACCESS_FINE_LOCATION
    private val bgLoc = Manifest.permission.ACCESS_BACKGROUND_LOCATION
    private val appsControl = UserManager.DISALLOW_APPS_CONTROL
    private val windows = UserManager.DISALLOW_CREATE_WINDOWS
    private val reset = UserManager.DISALLOW_FACTORY_RESET
    private val unknownSources = UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES

    private fun device(policy: Int = PERMISSION_POLICY_PROMPT) = FakeGuardDevice(
        policy = policy,
        apps = mutableMapOf(
            "com.kiosk" to listOf(camera, fine, bgLoc),
            "com.other" to listOf(camera),
            AGENT to listOf(fine),
        ),
    )

    private fun guard(
        d: FakeGuardDevice,
        records: GuardRecordStore = LoggedRecords(d),
        factoryReset: Boolean? = null,
        rules: Set<String> = emptySet(),
        wiping: Boolean = false,
    ) = KioskGuard(d, records, AGENT, { factoryReset }, { rules }, { wiping }) { false }

    @Test fun `arming saves its record before it changes the device, then holds every protection`() {
        val d = device()
        val g = guard(d)
        assertEquals(KioskResult.Ok, g.arm(setOf("com.kiosk")))
        assertEquals("the record comes first", "record:save", d.log.first())
        assertEquals(PERMISSION_POLICY_AUTO_GRANT, d.policy)
        assertEquals(setOf("com.kiosk"), d.uninstallBlocked)
        assertEquals(setOf(appsControl, windows, unknownSources, reset), d.restrictions)
        assertTrue(g.held())
        assertTrue(g.protects("com.kiosk"))
        assertFalse(g.protects("com.other"))
    }

    @Test fun `the agent itself is never among the kiosk apps it protects`() {
        val d = device()
        val g = guard(d)
        g.arm(setOf("com.kiosk", AGENT))
        assertEquals(setOf("com.kiosk"), d.uninstallBlocked)
        assertFalse(g.protects(AGENT))
    }

    @Test fun `arming again changes nothing and never rewrites what was saved first`() {
        val d = device()
        val records = LoggedRecords(d)
        val g = guard(d, records)
        g.arm(setOf("com.kiosk"))
        val first = records.record
        d.log.clear()
        assertEquals(KioskResult.Ok, g.arm(setOf("com.kiosk")))
        assertEquals("no change, no write", emptyList<String>(), d.log.filterNot { it.startsWith("uninstallBlocked") })
        // A new guard (a new process) reads the device's policy as auto-grant now; the prior one stays the saved one.
        guard(d, records).arm(setOf("com.kiosk"))
        assertEquals(first, records.record)
        assertEquals(PERMISSION_POLICY_PROMPT, records.record?.priorPolicy)
    }

    @Test fun `a policy the device doesn't take is reported`() {
        val d = device().apply { ignorePolicy = true }
        val r = guard(d).arm(setOf("com.kiosk"))
        assertTrue(r is KioskResult.Failed)
        assertTrue("the others are still made", "com.kiosk" in d.uninstallBlocked && appsControl in d.restrictions)
    }

    @Test fun `an app of the kiosk not installed yet is protected once it is, without failing the arm`() {
        val d = device()
        val g = guard(d)
        assertEquals(KioskResult.Ok, g.arm(setOf("com.kiosk", "com.later")))
        assertEquals(setOf("com.kiosk"), d.uninstallBlocked)
        d.apps["com.later"] = listOf(camera)
        g.arm(setOf("com.kiosk", "com.later"))
        assertEquals(setOf("com.kiosk", "com.later"), d.uninstallBlocked)
    }

    @Test fun `where the admin can't grant sensors, only the kiosk apps' unanswerable ones are denied`() {
        val d = device().apply { sensorsGrantable = false }
        d.apps["com.kiosk2"] = listOf(bgLoc)
        d.fixed[KioskGuard.pair("com.kiosk2", bgLoc)] = PERMISSION_GRANT_STATE_GRANTED // a rule grants it: kept
        d.apps["com.other"] = listOf(bgLoc)
        val records = LoggedRecords(d)
        guard(d, records).arm(setOf("com.kiosk", "com.kiosk2"))
        assertEquals(PERMISSION_GRANT_STATE_DENIED, d.fixed[KioskGuard.pair("com.kiosk", bgLoc)])
        assertEquals(PERMISSION_GRANT_STATE_GRANTED, d.fixed[KioskGuard.pair("com.kiosk2", bgLoc)])
        assertNull("not a kiosk app", d.fixed[KioskGuard.pair("com.other", bgLoc)])
        assertNull("answerable ones are left to the policy", d.fixed[KioskGuard.pair("com.kiosk", fine)])
        assertEquals(setOf(KioskGuard.pair("com.kiosk", bgLoc)), records.record?.denied)
        val save = d.log.lastIndexOf("record:save")
        val denied = d.log.indexOf("grant:com.kiosk|$bgLoc:$PERMISSION_GRANT_STATE_DENIED")
        assertTrue("saved before it is set", save < denied)
    }

    @Test fun `where the admin can grant sensors, nothing is denied`() {
        val d = device()
        guard(d).arm(setOf("com.kiosk"))
        assertTrue(d.fixed.isEmpty())
    }

    @Test fun `an app already granted it is not denied`() {
        val d = device().apply { sensorsGrantable = false }
        d.granted += KioskGuard.pair("com.kiosk", bgLoc)
        guard(d).arm(setOf("com.kiosk"))
        assertNull(d.fixed[KioskGuard.pair("com.kiosk", bgLoc)])
    }

    @Test fun `disarming after a device that asked puts it back and releases only the grants fixed in kiosk`() {
        val d = device()
        d.fixed[KioskGuard.pair("com.other", camera)] = PERMISSION_GRANT_STATE_DENIED // fixed before kiosk: kept
        d.fixed[KioskGuard.pair(AGENT, fine)] = PERMISSION_GRANT_STATE_GRANTED // the agent's own: kept
        val rule = KioskGuard.pair("com.kiosk", fine)
        val records = LoggedRecords(d)
        val g = guard(d, records, rules = setOf(rule))
        g.arm(setOf("com.kiosk"))
        d.requestUnderPolicy("com.kiosk", camera)
        d.requestUnderPolicy("com.kiosk", fine) // named by a configuration rule: the rule owns it
        assertEquals(KioskResult.Ok, g.disarm())
        assertEquals(PERMISSION_POLICY_PROMPT, d.policy)
        assertNull("released", d.fixed[KioskGuard.pair("com.kiosk", camera)])
        assertTrue("but still granted", KioskGuard.pair("com.kiosk", camera) in d.granted)
        assertEquals(PERMISSION_GRANT_STATE_GRANTED, d.fixed[rule])
        assertEquals(PERMISSION_GRANT_STATE_DENIED, d.fixed[KioskGuard.pair("com.other", camera)])
        assertEquals(PERMISSION_GRANT_STATE_GRANTED, d.fixed[KioskGuard.pair(AGENT, fine)])
        assertTrue(d.uninstallBlocked.isEmpty())
        assertTrue("factory reset back to what it was (not blocked)", d.restrictions.isEmpty())
        assertEquals("the record is cleared last", "record:clear", d.log.last())
        assertNull(records.record)
        assertFalse(g.held())
    }

    @Test fun `the guard's own denials are released on disarm`() {
        val d = device().apply { sensorsGrantable = false }
        val g = guard(d)
        g.arm(setOf("com.kiosk"))
        g.disarm()
        assertNull(d.fixed[KioskGuard.pair("com.kiosk", bgLoc)])
        assertFalse("released to not granted", KioskGuard.pair("com.kiosk", bgLoc) in d.granted)
    }

    @Test fun `a configuration rule on a pair the guard denied is never reset when kiosk ends`() {
        val d = device().apply { sensorsGrantable = false }
        val pair = KioskGuard.pair("com.kiosk", bgLoc)
        var rules = emptySet<String>()
        val records = LoggedRecords(d)
        val g = KioskGuard(d, records, AGENT, permissionRules = { rules }) { false }
        g.arm(setOf("com.kiosk"))
        assertEquals(PERMISSION_GRANT_STATE_DENIED, d.fixed[pair])
        rules = setOf(pair) // the admin adds a Deny rule for it while in kiosk (applied as DENIED)
        assertEquals(KioskResult.Ok, g.disarm())
        assertEquals("the rule's state stands", PERMISSION_GRANT_STATE_DENIED, d.fixed[pair])
    }

    @Test fun `a switch leaves a rule's pair of the old kiosk as the rule set it`() {
        val d = device().apply { sensorsGrantable = false }
        d.apps["com.next"] = listOf(camera)
        val pair = KioskGuard.pair("com.kiosk", bgLoc)
        var rules = emptySet<String>()
        val records = LoggedRecords(d)
        val g = KioskGuard(d, records, AGENT, permissionRules = { rules }) { false }
        g.arm(setOf("com.kiosk"))
        rules = setOf(pair)
        g.arm(setOf("com.next"))
        g.commit(setOf("com.next"))
        assertEquals(PERMISSION_GRANT_STATE_DENIED, d.fixed[pair])
        assertEquals(emptySet<String>(), records.record?.denied)
    }

    @Test fun `a rule granting a pair the guard denied is handed over, not denied again`() {
        val d = device().apply { sensorsGrantable = false }
        val pair = KioskGuard.pair("com.kiosk", bgLoc)
        var rules = emptySet<String>()
        val records = LoggedRecords(d)
        val g = KioskGuard(d, records, AGENT, permissionRules = { rules }) { false }
        g.arm(setOf("com.kiosk"))
        rules = setOf(pair)
        d.setGrantState("com.kiosk", bgLoc, PERMISSION_GRANT_STATE_GRANTED) // the rule, applied
        d.log.clear()
        g.arm(setOf("com.kiosk"))
        assertEquals(PERMISSION_GRANT_STATE_GRANTED, d.fixed[pair])
        assertTrue(d.log.none { it.startsWith("grant:") })
        assertEquals(emptySet<String>(), records.record?.denied)
    }

    @Test fun `every grant-state write of the guard holds the lock the configuration's rules take`() {
        val d = device().apply { sensorsGrantable = false }
        val lock = PermissionGrantLock()
        val unlocked = mutableListOf<String>()
        d.beforeChange = { if (it.startsWith("grant:") && !lock.isHeldByCurrentThread()) unlocked += it }
        val g = KioskGuard(d, LoggedRecords(d), AGENT, grantLock = lock) { false }
        g.arm(setOf("com.kiosk"))
        d.requestUnderPolicy("com.kiosk", camera)
        g.disarm()
        assertTrue(d.log.any { it.startsWith("grant:") })
        assertEquals(emptyList<String>(), unlocked)
    }

    @Test fun `a snapshot that can't be taken releases no grant after kiosk`() {
        for (fault in listOf("unlistable", "unreadable state")) {
            val d = device()
            d.fixed[KioskGuard.pair("com.other", camera)] = PERMISSION_GRANT_STATE_DENIED // fixed before kiosk
            if (fault == "unlistable") {
                d.unlistable = true
            } else {
                d.unreadableStates += KioskGuard.pair("com.other", camera)
            }
            val records = LoggedRecords(d)
            val g = guard(d, records)
            g.arm(setOf("com.kiosk"))
            assertNull(fault, records.record?.fixedBefore)
            d.unlistable = false
            d.unreadableStates.clear()
            d.requestUnderPolicy("com.kiosk", camera)
            g.disarm()
            assertEquals(fault, PERMISSION_GRANT_STATE_DENIED, d.fixed[KioskGuard.pair("com.other", camera)])
            val kept = d.fixed[KioskGuard.pair("com.kiosk", camera)]
            assertEquals("kept fixed, the safe side", PERMISSION_GRANT_STATE_GRANTED, kept)
        }
    }

    @Test fun `a device that granted already keeps its grants fixed, and no snapshot is taken`() {
        val d = device(PERMISSION_POLICY_AUTO_GRANT)
        val g = guard(d)
        g.arm(setOf("com.kiosk"))
        assertEquals("no snapshot of every app", 0, d.installedListings)
        d.requestUnderPolicy("com.kiosk", camera)
        g.disarm()
        assertEquals(PERMISSION_POLICY_AUTO_GRANT, d.policy)
        assertEquals(PERMISSION_GRANT_STATE_GRANTED, d.fixed[KioskGuard.pair("com.kiosk", camera)])
    }

    @Test fun `a device that denied goes back to denying`() {
        val d = device(PERMISSION_POLICY_AUTO_DENY)
        val g = guard(d)
        g.arm(setOf("com.kiosk"))
        g.disarm()
        assertEquals(PERMISSION_POLICY_AUTO_DENY, d.policy)
    }

    @Test fun `factory reset after kiosk follows the configuration, else what it was`() {
        for ((setting, before, after) in listOf(
            Triple(false, false, true), Triple(true, true, false), Triple(null, true, true), Triple(null, false, false),
        )) {
            val d = device()
            if (before) d.restrictions += reset
            val g = guard(d, factoryReset = setting)
            g.arm(setOf("com.kiosk"))
            assertTrue(reset in d.restrictions)
            g.disarm()
            assertEquals("setting=$setting before=$before", after, reset in d.restrictions)
        }
    }

    @Test fun `factory reset is not blocked while a wipe is under way`() {
        val d = device()
        guard(d, wiping = true).arm(setOf("com.kiosk"))
        assertFalse(reset in d.restrictions)
        assertTrue(appsControl in d.restrictions)
    }

    @Test fun `a switch protects both kiosks until the commit, which releases the old one's apps`() {
        val d = device().apply { sensorsGrantable = false }
        d.apps["com.next"] = listOf(camera)
        val records = LoggedRecords(d)
        val g = guard(d, records)
        g.arm(setOf("com.kiosk"))
        g.arm(setOf("com.next"))
        assertEquals(setOf("com.kiosk", "com.next"), d.uninstallBlocked)
        assertEquals(KioskResult.Ok, g.commit(setOf("com.next")))
        assertEquals(setOf("com.next"), d.uninstallBlocked)
        assertEquals(setOf("com.next"), records.record?.packages)
        assertNull("its denial released too", d.fixed[KioskGuard.pair("com.kiosk", bgLoc)])
        assertEquals(emptySet<String>(), records.record?.denied)
        assertEquals("still armed", PERMISSION_POLICY_AUTO_GRANT, d.policy)
    }

    @Test fun `an unwritable record still arms the device, and says so`() {
        val d = device()
        val records = LoggedRecords(d).apply { writable = false }
        val g = guard(d, records)
        assertTrue(g.arm(setOf("com.kiosk")) is KioskResult.Failed)
        assertEquals(PERMISSION_POLICY_AUTO_GRANT, d.policy)
        assertEquals(setOf("com.kiosk"), d.uninstallBlocked)
        assertTrue("this process still knows it is armed", g.held())
        assertTrue("a record that can't be cleared is kept for the next run", g.disarm() is KioskResult.Failed)
        assertTrue(g.protects("com.kiosk"))
        assertFalse("but factory reset is the configuration's again", g.held())
    }

    @Test fun `a lost record disarms what it can and leaves the policy on auto-grant`() {
        val d = device()
        guard(d).arm(setOf("com.kiosk"))
        val fresh = guard(d, LoggedRecords(d)) // the record is gone
        assertEquals(KioskResult.Ok, fresh.disarm(alsoUnblock = setOf("com.kiosk")))
        assertEquals(PERMISSION_POLICY_AUTO_GRANT, d.policy)
        assertTrue(d.uninstallBlocked.isEmpty())
        assertFalse(appsControl in d.restrictions)
        assertTrue("not managed and unknown before: left as it is", reset in d.restrictions)
    }

    @Test fun `outside kiosk, a lost record is settled from the restrictions only the guard sets`() {
        val d = device()
        val records = LoggedRecords(d)
        guard(d, records).arm(setOf("com.kiosk"))
        records.record = null // storage lost
        val fresh = guard(d, records)
        assertEquals(KioskResult.Ok, fresh.settleOutsideKiosk())
        assertFalse(appsControl in d.restrictions)
        assertFalse(windows in d.restrictions)
        assertTrue("every app it blocked is unblocked", d.uninstallBlocked.isEmpty())
        assertEquals("the policy is left (0.5.x configurations don't set one)", PERMISSION_POLICY_AUTO_GRANT, d.policy)
    }

    @Test fun `installs from unknown sources are blocked in kiosk and allowed again after`() {
        val d = device()
        val g = guard(d)
        g.arm(setOf("com.kiosk"))
        assertTrue(unknownSources in d.restrictions)
        g.disarm()
        assertFalse(unknownSources in d.restrictions)
    }

    @Test fun `a lost record is settled even when only the unknown-sources restriction is left`() {
        val d = device()
        val records = LoggedRecords(d)
        guard(d, records).arm(setOf("com.kiosk"))
        records.record = null
        d.restrictions -= appsControl // a cut-off disarm got this far
        d.restrictions -= windows
        assertEquals(KioskResult.Ok, guard(d, records).settleOutsideKiosk())
        assertFalse(unknownSources in d.restrictions)
    }

    @Test fun `outside kiosk with no record and none of its restrictions, nothing is touched`() {
        val d = device()
        d.restrictions += reset // a configuration's Block
        d.uninstallBlocked += "com.other"
        assertEquals(KioskResult.Ok, guard(d).settleOutsideKiosk())
        assertTrue(d.log.isEmpty())
    }

    @Test fun `a lost record settled outside kiosk puts factory reset to the configuration's setting`() {
        val d = device()
        val records = LoggedRecords(d)
        guard(d, records).arm(setOf("com.kiosk"))
        records.record = null
        guard(d, records, factoryReset = true).settleOutsideKiosk()
        assertFalse(reset in d.restrictions)
    }

    @Test fun `an unreadable record is never overwritten, nor undone`() {
        val d = device().apply { sensorsGrantable = false } // the arm also denies, which saves the record as it goes
        val records = LoggedRecords(d)
        guard(d, records).arm(setOf("com.kiosk"))
        val saved = records.record
        records.unreadable = true
        d.policy = PERMISSION_POLICY_PROMPT // something reset it while the record can't be read
        d.fixed -= KioskGuard.pair("com.kiosk", bgLoc) // and the denial: the arm must deny again, saving nothing
        val g = guard(d, records)
        assertTrue("reported", g.arm(setOf("com.kiosk")) is KioskResult.Failed)
        assertEquals("still protected", PERMISSION_POLICY_AUTO_GRANT, d.policy)
        assertEquals("the saved record is untouched", saved, records.record)
        assertTrue(g.held())
        assertTrue(g.disarm() is KioskResult.Failed)
        assertTrue(g.settleOutsideKiosk() is KioskResult.Failed)
        assertTrue("nothing undone", appsControl in d.restrictions && "com.kiosk" in d.uninstallBlocked)
        records.unreadable = false
        assertEquals("once readable, it is used", KioskResult.Ok, g.disarm())
        assertEquals(PERMISSION_POLICY_PROMPT, d.policy)
    }

    @Test fun `a guard whose record can't be read counts as armed`() {
        val d = device()
        val records = LoggedRecords(d).apply { unreadable = true }
        val g = guard(d, records)
        assertTrue(g.armed())
        records.unreadable = false
        assertFalse("readable, and none: not armed", g.armed())
    }

    @Test fun `a kiosk ending during a wipe never blocks factory reset again`() {
        val d = device()
        var wiping = false
        val g = KioskGuard(d, LoggedRecords(d), AGENT, factoryResetSetting = { false }, wiping = { wiping }) { false }
        g.arm(setOf("com.kiosk"))
        wiping = true
        d.restrictions -= reset // the wipe lifted it
        assertEquals(KioskResult.Ok, g.disarm())
        assertFalse(reset in d.restrictions)
    }

    @Test fun `a prior policy that can't be read is taken as auto-grant, never put back to asking`() {
        val d = device()
        d.policyUnreadable = true
        val records = LoggedRecords(d)
        val g = guard(d, records)
        g.arm(setOf("com.kiosk"))
        assertEquals(PERMISSION_POLICY_AUTO_GRANT, records.record?.priorPolicy)
        d.policyUnreadable = false
        g.disarm()
        assertEquals(PERMISSION_POLICY_AUTO_GRANT, d.policy)
    }

    @Test fun `an app whose block can't be lifted stays protected, and is tried again`() {
        val d = device()
        d.apps["com.next"] = listOf(camera)
        val records = LoggedRecords(d)
        val g = guard(d, records)
        g.arm(setOf("com.kiosk"))
        d.stuckBlocked += "com.kiosk"
        g.arm(setOf("com.next"))
        assertTrue(g.commit(setOf("com.next")) is KioskResult.Failed)
        assertEquals(setOf("com.kiosk", "com.next"), records.record?.packages)
        d.stuckBlocked.clear()
        assertEquals(KioskResult.Ok, g.commit(setOf("com.next")))
        assertEquals(setOf("com.next"), d.uninstallBlocked)
    }

    @Test fun `a factory reset setting received in kiosk is what the device gets at exit`() {
        for (blocked in listOf(true, false)) {
            val d = device()
            d.apps["com.next"] = listOf(camera)
            val g = guard(d) // the configuration doesn't manage it
            g.arm(setOf("com.kiosk"))
            g.remember(blocked) // a policy.apply while in kiosk
            g.arm(setOf("com.next")) // a later save from the guard keeps it
            g.disarm()
            assertEquals("blocked=$blocked", blocked, reset in d.restrictions)
        }
    }

    @Test fun `a setting received while a guard step runs survives that step's own save`() {
        val d = device().apply { sensorsGrantable = false } // the arm saves again after the policy (its denial)
        val g = guard(d)
        g.arm(setOf("com.kiosk"))
        d.policy = PERMISSION_POLICY_PROMPT // so the next arm sets it again, a step this test hooks
        d.apps["com.kiosk2"] = listOf(bgLoc) // and a new denial after it saves from the copy the arm read first
        d.beforeChange = { if (it == "policy:$PERMISSION_POLICY_AUTO_GRANT") g.remember(true) }
        g.arm(setOf("com.kiosk", "com.kiosk2"))
        d.beforeChange = {}
        g.disarm()
        assertTrue(reset in d.restrictions)
    }

    @Test fun `a factory reset setting counts as kept only once it is persisted`() {
        val d = device()
        val records = LoggedRecords(d)
        val g = guard(d, records)
        assertFalse("no record: nothing would carry it to the exit", g.remember(true))
        g.arm(setOf("com.kiosk"))
        assertTrue(g.remember(true))
        assertEquals(true, records.record?.factoryResetBefore)
        records.writable = false
        assertFalse("not saved", g.remember(false))
        records.writable = true
        g.disarm()
        assertTrue("the exit follows what was persisted, the block", reset in d.restrictions)
        g.arm(setOf("com.kiosk"))
        records.unreadable = true
        assertFalse("unreadable in a new process", guard(d, records).remember(false))
    }

    @Test fun `once a disarm has put factory reset back, the guard no longer holds it, even if it stops there`() {
        val d = device()
        val g = guard(d, factoryReset = true)
        g.arm(setOf("com.kiosk"))
        d.stuckBlocked += "com.kiosk" // a later step the device won't take: the record stays
        assertTrue(g.disarm() is KioskResult.Failed)
        assertFalse("factory reset is the configuration's again", reset in d.restrictions)
        assertFalse(g.held())
        g.arm(setOf("com.kiosk"))
        assertTrue("held again in kiosk", g.held())
    }

    @Test fun `a re-arm that couldn't be saved still holds factory reset after a restart while locked`() {
        val d = device()
        val records = LoggedRecords(d)
        guard(d, records).arm(setOf("com.kiosk"))
        // A disarm put Factory reset back but stopped there: the record stays, marked releasing.
        records.record = records.record!!.copy(releasing = true)
        // Kiosk again; the arm's record write fails (storage), the device is armed anyway.
        records.writable = false
        var locked = true
        KioskGuard(d, records, AGENT) { locked }.arm(setOf("com.kiosk"))
        assertTrue(reset in d.restrictions)
        // A restart while still locked reads the older record.
        val after = KioskGuard(d, records, AGENT) { locked }
        assertTrue("held while locked", after.held())
        locked = false
        assertFalse("out of kiosk, the configuration's again", after.held())
        assertTrue("still armed for the run that finishes it", after.armed())
    }

    @Test fun `a managed configuration still has the last word at exit`() {
        val d = device()
        val records = LoggedRecords(d)
        val g = guard(d, records, factoryReset = true)
        g.arm(setOf("com.kiosk"))
        assertTrue(g.remember(true))
        assertEquals("still kept, should it stop managing it", true, records.record?.factoryResetBefore)
        records.writable = false
        assertTrue("managed: not lost, saved or not", g.remember(true))
        records.writable = true
        g.disarm()
        assertFalse(reset in d.restrictions)
    }

    @Test fun `not the Device Owner, nothing is done`() {
        val d = device().apply { owner = false }
        val g = guard(d)
        assertEquals(KioskResult.Unsupported, g.arm(setOf("com.kiosk")))
        assertEquals(KioskResult.Unsupported, g.disarm())
        assertTrue(d.log.isEmpty())
    }

    /**
     * Cut the process off before each change an arm and a disarm make, then let a new process (a new guard on the
     * same record and device) run the same operation again: it always ends fully armed or fully disarmed, with the
     * policy that came before.
     */
    @Test fun `a death between any two steps is finished by the next run`() {
        val steps = runSteps { g -> g.arm(setOf("com.kiosk")) }
        for (k in 0 until steps) {
            val d = device().apply { sensorsGrantable = false }
            val records = LoggedRecords(d)
            cutAt(d, k) { guard(d, records).arm(setOf("com.kiosk")) }
            assertEquals("arm, cut at $k", KioskResult.Ok, guard(d, records).arm(setOf("com.kiosk")))
            assertArmed(d, records, "arm cut at $k")
            assertEquals(KioskResult.Ok, guard(d, records).disarm())
            assertDisarmed(d, records, "arm cut at $k, then disarmed")
        }
        val disarmSteps = runSteps { g -> g.arm(setOf("com.kiosk")); g.disarm() } - steps
        for (k in 0 until disarmSteps) {
            val d = device().apply { sensorsGrantable = false }
            val records = LoggedRecords(d)
            guard(d, records).arm(setOf("com.kiosk"))
            d.requestUnderPolicy("com.kiosk", camera)
            cutAt(d, k) { guard(d, records).disarm() }
            assertEquals("disarm, cut at $k", KioskResult.Ok, guard(d, records).disarm())
            assertDisarmed(d, records, "disarm cut at $k")
            assertNull("cut at $k: the kiosk's grant released", d.fixed[KioskGuard.pair("com.kiosk", camera)])
        }
    }

    private fun runSteps(op: (KioskGuard) -> Unit): Int {
        val d = device().apply { sensorsGrantable = false }
        var n = 0
        d.beforeChange = { n++ }
        op(guard(d, LoggedRecords(d)))
        return n
    }

    private fun cutAt(d: FakeGuardDevice, k: Int, op: () -> Unit) {
        var n = 0
        d.beforeChange = { if (n++ == k) throw ProcessCut() }
        runCatching(op)
        d.beforeChange = {}
    }

    private fun assertArmed(d: FakeGuardDevice, records: LoggedRecords, why: String) {
        assertEquals(why, PERMISSION_POLICY_AUTO_GRANT, d.policy)
        assertEquals(why, PERMISSION_POLICY_PROMPT, records.record?.priorPolicy)
        assertEquals(why, setOf("com.kiosk"), d.uninstallBlocked)
        assertEquals(why, setOf(appsControl, windows, unknownSources, reset), d.restrictions)
        assertEquals(why, PERMISSION_GRANT_STATE_DENIED, d.fixed[KioskGuard.pair("com.kiosk", bgLoc)])
    }

    private fun assertDisarmed(d: FakeGuardDevice, records: LoggedRecords, why: String) {
        assertEquals(why, PERMISSION_POLICY_PROMPT, d.policy)
        assertTrue(why, d.uninstallBlocked.isEmpty())
        assertTrue(why, d.restrictions.isEmpty())
        assertNull(why, d.fixed[KioskGuard.pair("com.kiosk", bgLoc)])
        assertNull(why, records.record)
    }

    private companion object {
        const val AGENT = "com.mdmesh.agent"
    }
}
