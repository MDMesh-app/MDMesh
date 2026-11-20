package com.mdmesh.core.config

import android.content.ComponentName
import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.command.handlers.ConfigApplyHandler
import com.mdmesh.core.command.handlers.DeviceWipeHandler
import com.mdmesh.core.command.handlers.PolicyApplyHandler
import com.mdmesh.core.command.handlers.WipeTarget
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskHomeSwitch
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.policy.TogglePolicyResolver
import com.mdmesh.policy.UserRestrictions
import com.mdmesh.policy.factoryreset.FactoryResetPolicy
import com.mdmesh.policy.factoryreset.FactoryResetWipeHold
import com.mdmesh.policy.factoryreset.InMemoryWipeHoldStore
import com.mdmesh.policy.factoryreset.WipeHeldFactoryResetPolicy
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigLocation
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #222 (PR #227 review): the wipe call can return before the reboot into recovery. No configuration apply after an
 * accepted wipe may put Factory reset: Block back, or a vendor recovery refuses to format.
 */
class WipeHoldsFactoryResetTest {

    /** One device: our admin's restrictions, written by both the wipe handler and the factory reset policy. */
    private class Device(blocked: Boolean, var wipeError: Exception? = null) : WipeTarget {
        val restrictions = if (blocked) mutableSetOf(KEY) else mutableSetOf()
        override fun isDeviceOwner() = true
        override fun holdsRestriction(key: String) = key in restrictions
        override fun hasRestriction(key: String) = key in restrictions
        override fun addRestriction(key: String) {
            restrictions += key
        }
        override fun clearRestriction(key: String) {
            restrictions -= key
        }
        var wipes = 0
        override fun wipe() {
            wipes++
            wipeError?.let { throw it } // otherwise returns, as wipeDevice can before the reboot
        }
    }

    private class RestrictionPolicy(private val device: Device) : FactoryResetPolicy {
        override val capabilityKey = FactoryResetPolicy.CAPABILITY_KEY
        override fun isSupported() = true
        override fun setEnabled(enabled: Boolean): PolicyOutcome {
            if (enabled) device.clearRestriction(KEY) else device.addRestriction(KEY)
            return PolicyOutcome.Applied
        }
    }

    private class NoKiosk : KioskController {
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int) =
            KioskResult.Ok
        override fun exit() = KioskResult.Ok
        override fun isLocked(context: android.content.Context) = false
        override fun allowedPackages(): List<String> = emptyList()
    }

    private object NoHome : KioskHomeSwitch {
        override fun setClaimEnabled(enabled: Boolean) = Unit
        override fun showLauncher() = Unit
        override fun showOemHome() = Unit
    }

    private val block = ConfigApplyPayload(revision = "r1", policies = mapOf("factoryReset" to false))

    private val wipeCommand = CommandEnvelope(commandId = "w1", issuedAt = "2026-01-01T00:00:00Z", type = "device.wipe")

    private val store = InMemoryConfigStateStore()

    // As CapabilityRegistry builds it: FactoryResetPolicyFactory wraps every strategy in the hold.
    private fun toggles(device: Device, hold: FactoryResetWipeHold) = TogglePolicyResolver(
        linkedMapOf<String, () -> TogglePolicy?>(
            "factoryReset" to { WipeHeldFactoryResetPolicy(RestrictionPolicy(device), hold) },
        ),
    )

    private fun applier(device: Device, hold: FactoryResetWipeHold): ConfigApplier {
        val kiosk = KioskApplier(NoKiosk(), InMemoryKioskStateStore(), NoHome, ComponentName("a", "b")) { false }
        // A location mode of FAIL fails its section: the document is delivered but never saved.
        val setLocation: (String) -> Unit = { check(it != FAIL) { "location refused" } }
        return ConfigApplier(toggles(device, hold)::current, kiosk, setLocation, store, setPowerMode = {})
    }

    private fun wipeHandler(device: Device, hold: FactoryResetWipeHold, applier: ConfigApplier) =
        DeviceWipeHandler(device, hold, markConfigStale = store::markStale) { wipe -> applier.exclusive(wipe) }

    @Test
    fun `after an accepted wipe neither config apply nor the boot re-apply sets the block`() = runTest {
        val device = Device(blocked = true)
        val hold = FactoryResetWipeHold()
        val applier = applier(device, hold)
        applier.apply(block) // saved, so the re-apply has a document

        val wiped = wipeHandler(device, hold, applier).handle(wipeCommand)
        val apply = applier.apply(block.copy(revision = "r2"))
        val reapply = applier.reapplyPersisted()!!

        val deferred = ConfigOutcome.deferred(WipeHeldFactoryResetPolicy.WIPING)
        assertEquals(CommandStatus.DONE, wiped.status)
        assertEquals(deferred, apply.outcomes["policies.factoryReset"])
        assertEquals(deferred, reapply.outcomes["policies.factoryReset"])
        assertFalse(KEY in device.restrictions)
    }

    /**
     * No reboot came: the hold expires and the agent re-applies on its own (the server's re-send can wait an hour).
     * The expiry is seen inside an apply, under the configuration lock, so it only schedules the re-apply.
     */
    @Test
    fun `an expired hold schedules a re-apply that puts the block back`() = runTest {
        val device = Device(blocked = true)
        var nowMs = 0L
        var scheduled = 0
        val hold = FactoryResetWipeHold(nowMs = { nowMs }, onExpired = { scheduled++ })
        val applier = applier(device, hold)
        applier.apply(block)
        wipeHandler(device, hold, applier).handle(wipeCommand)

        nowMs = FactoryResetWipeHold.MAX_HOLD_MS
        assertFalse(hold.held) // the next command's check (CommandDispatcher) sees the expiry

        assertEquals(1, scheduled)
        assertFalse("nothing put it back yet", KEY in device.restrictions)
        // What the scheduled ConfigReapplyWorker then runs.
        val reapply = applier.reapplyPersisted()!!
        assertEquals(ConfigOutcome.APPLIED, reapply.outcomes["policies.factoryReset"])
        assertTrue(KEY in device.restrictions)
    }

    /**
     * PR #227 review: no command, no apply and no server after the wipe (the device went offline). The check the hold
     * scheduled (WipeHoldExpiryWorker) ends it, reports it once, and the re-apply puts the block back.
     */
    @Test
    fun `with nothing else after the wipe the scheduled check puts the block back and reports it once`() = runTest {
        val device = Device(blocked = true)
        var nowMs = 0L
        var reported = 0
        val checks = mutableListOf<Long>()
        val hold = FactoryResetWipeHold(nowMs = { nowMs }, onHold = { checks += FactoryResetWipeHold.MAX_HOLD_MS }) {
            reported++
        }
        val applier = applier(device, hold)
        applier.apply(block)
        wipeHandler(device, hold, applier).handle(wipeCommand)
        assertEquals("one check at the deadline, asked by the wipe", listOf(FactoryResetWipeHold.MAX_HOLD_MS), checks)

        nowMs = FactoryResetWipeHold.MAX_HOLD_MS
        assertEquals(null, hold.remainingMs) // what the worker reads
        applier.reapplyPersisted() // what it, and onExpired, schedule

        assertTrue(KEY in device.restrictions)
        assertFalse(hold.held) // a later command's read
        assertEquals("wipeNotStarted once", 1, reported)
    }

    /**
     * PR #227 review, scenario A: the admin sets Factory reset to Allow while a wipe that didn't start is held. The
     * config.apply runs (it isn't dropped as not run), so the expiry re-applies Allow, not the older Block.
     */
    @Test
    fun `a config apply during the hold runs, and the expiry re-applies the newest document`() = runTest {
        val device = Device(blocked = true)
        var nowMs = 0L
        val hold = FactoryResetWipeHold(nowMs = { nowMs })
        val applier = applier(device, hold)
        applier.apply(block)
        val dispatcher = dispatcher(device, hold, applier)
        dispatcher.dispatch(wipeCommand)

        val allow = dispatcher.dispatch(configApply("c2", block.copy(revision = "r2", policies = ALLOW)))
        nowMs = FactoryResetWipeHold.MAX_HOLD_MS
        assertFalse(hold.held)
        applier.reapplyPersisted() // what the expiry schedules

        assertEquals(CommandStatus.DONE, allow.status)
        assertEquals("r2", store.revision())
        assertFalse("Allow, as last set", KEY in device.restrictions)
    }

    /** A Block sent during the hold is deferred but saved: the expiry puts it in force. */
    @Test
    fun `a Block sent during the hold is saved and put in force at the expiry`() = runTest {
        val device = Device(blocked = false)
        var nowMs = 0L
        val hold = FactoryResetWipeHold(nowMs = { nowMs })
        val applier = applier(device, hold)
        val dispatcher = dispatcher(device, hold, applier)
        dispatcher.dispatch(wipeCommand)

        val sent = dispatcher.dispatch(configApply("c2", block.copy(revision = "r2")))
        assertFalse(KEY in device.restrictions)
        nowMs = FactoryResetWipeHold.MAX_HOLD_MS
        assertFalse(hold.held)
        applier.reapplyPersisted()

        assertEquals(CommandStatus.DONE, sent.status)
        assertEquals("r2", store.revision())
        assertTrue(KEY in device.restrictions)
    }

    /** Scenario B: a repeat wipe of a lost device isn't refused while the first one's hold lasts. */
    @Test
    fun `a repeat wipe during the hold wipes again and restarts the hold`() = runTest {
        val device = Device(blocked = true)
        var nowMs = 0L
        val checks = mutableListOf<Long>()
        val hold = FactoryResetWipeHold(nowMs = { nowMs }, onHold = { checks += nowMs })
        val applier = applier(device, hold)
        applier.apply(block)
        val dispatcher = dispatcher(device, hold, applier)
        dispatcher.dispatch(wipeCommand)

        nowMs = 300_000L
        val again = dispatcher.dispatch(wipeCommand.copy(commandId = "w2"))
        nowMs = FactoryResetWipeHold.MAX_HOLD_MS // the first hold's deadline, not the second's

        assertEquals(CommandStatus.DONE, again.status)
        assertEquals(2, device.wipes)
        assertEquals(listOf(0L, 300_000L), checks)
        assertTrue(hold.held)
        assertFalse(KEY in device.restrictions)
    }

    /**
     * PR #227 review: a repeat wipe that throws must not end the hold the accepted one set, or the next apply puts
     * Block back while that wipe is heading into recovery (#222).
     */
    @Test
    fun `a repeat wipe that throws keeps the earlier wipe's hold and the block off`() = runTest {
        val device = Device(blocked = true)
        val hold = FactoryResetWipeHold()
        val applier = applier(device, hold)
        applier.apply(block)
        val dispatcher = dispatcher(device, hold, applier)
        val first = dispatcher.dispatch(wipeCommand)

        device.wipeError = SecurityException("wipe refused")
        val second = dispatcher.dispatch(wipeCommand.copy(commandId = "w2"))
        val apply = applier.apply(block.copy(revision = "r2"))

        assertEquals(CommandStatus.DONE, first.status)
        assertEquals(CommandStatus.FAILED, second.status)
        assertTrue(hold.held)
        assertEquals(ConfigOutcome.deferred(WipeHeldFactoryResetPolicy.WIPING), apply.outcomes["policies.factoryReset"])
        assertFalse(KEY in device.restrictions)
    }

    /**
     * PR #227 review (Greptile): the saved configuration allows reset; a newer one blocks it but another section fails,
     * so it is delivered, applied in part (the block took effect) and never saved. However the hold ends, the
     * restriction must be that newest setting, not the saved Allow.
     */
    private suspend fun newerBlockNeverSaved(device: Device, applier: ConfigApplier) {
        applier.apply(block.copy(policies = ALLOW))
        val newer = applier.apply(block.copy(revision = "r2", location = ConfigLocation(FAIL)))
        assertFalse(ConfigApplier.succeeded(newer))
        assertEquals("r1", store.load()?.revision)
        assertTrue("the block took effect", KEY in device.restrictions)
    }

    @Test
    fun `the expiry puts back the newest delivered Block, not the saved Allow`() = runTest {
        val device = Device(blocked = false)
        var nowMs = 0L
        val hold = FactoryResetWipeHold(nowMs = { nowMs })
        val applier = applier(device, hold)
        newerBlockNeverSaved(device, applier)
        wipeHandler(device, hold, applier).handle(wipeCommand)
        assertFalse(KEY in device.restrictions)

        nowMs = FactoryResetWipeHold.MAX_HOLD_MS
        assertFalse(hold.held)
        applier.reapplyPersisted() // what the expiry schedules

        assertTrue(KEY in device.restrictions)
    }

    @Test
    fun `a reboot without the wipe puts back the newest delivered Block, not the saved Allow`() = runTest {
        val device = Device(blocked = false)
        val saved = InMemoryWipeHoldStore()
        var boot = 3L
        val before = FactoryResetWipeHold(saved, bootId = { boot }, nowMs = { 500_000L })
        val applierBefore = applier(device, before)
        newerBlockNeverSaved(device, applierBefore)
        wipeHandler(device, before, applierBefore).handle(wipeCommand)
        assertFalse(KEY in device.restrictions)

        boot = 4L
        val after = FactoryResetWipeHold(saved, bootId = { boot }, nowMs = { 1_000L })
        applier(device, after).reapplyPersisted()

        assertTrue(KEY in device.restrictions)
    }

    @Test
    fun `a wipe that throws puts back the newest delivered Block, not the saved Allow`() = runTest {
        val device = Device(blocked = false)
        val hold = FactoryResetWipeHold()
        val applier = applier(device, hold)
        newerBlockNeverSaved(device, applier)
        device.wipeError = SecurityException("wipe refused")

        val wiped = wipeHandler(device, hold, applier).handle(wipeCommand)

        assertEquals(CommandStatus.FAILED, wiped.status)
        assertFalse(hold.held)
        assertTrue(KEY in device.restrictions)
    }

    /** Recording the event throws (a full disk): the read that saw the expiry still returns, and the hold ends once. */
    @Test
    fun `an expiry whose report throws still ends the hold and lets the apply through`() = runTest {
        val device = Device(blocked = true)
        var nowMs = 0L
        var scheduled = 0
        val hold = FactoryResetWipeHold(nowMs = { nowMs }) {
            scheduled++ // the re-apply is scheduled first
            error("event log unwritable")
        }
        val applier = applier(device, hold)
        applier.apply(block)
        wipeHandler(device, hold, applier).handle(wipeCommand)

        nowMs = FactoryResetWipeHold.MAX_HOLD_MS
        val apply = applier.apply(block.copy(revision = "r2"))

        assertEquals(ConfigOutcome.APPLIED, apply.outcomes["policies.factoryReset"])
        assertTrue(KEY in device.restrictions)
        assertFalse(hold.held)
        assertEquals(1, scheduled)
    }

    /** The wipe threw: the hold is released, so the check that comes later neither reports nor holds anything. */
    @Test
    fun `a wipe that throws leaves the scheduled check nothing to end`() = runTest {
        val device = Device(blocked = true, wipeError = SecurityException("wipe refused"))
        var nowMs = 0L
        var reported = 0
        val hold = FactoryResetWipeHold(nowMs = { nowMs }) { reported++ }
        wipeHandler(device, hold, applier(device, hold)).handle(wipeCommand)

        nowMs = FactoryResetWipeHold.MAX_HOLD_MS
        assertEquals(null, hold.remainingMs)

        assertEquals(0, reported)
        assertTrue(KEY in device.restrictions)
    }

    /** A reboot without the wipe ends the per-boot hold: the boot re-apply puts the block back, no server needed. */
    @Test
    fun `a reboot that did not wipe gets the block back from the boot re-apply`() = runTest {
        val device = Device(blocked = true)
        val saved = InMemoryWipeHoldStore()
        var boot = 3L
        var reported = 0
        val before = FactoryResetWipeHold(saved, bootId = { boot }, nowMs = { 500_000L })
        val applierBefore = applier(device, before)
        applierBefore.apply(block)
        wipeHandler(device, before, applierBefore).handle(wipeCommand)
        assertFalse(KEY in device.restrictions)

        boot = 4L // the new process after the reboot, early in its uptime
        val after = FactoryResetWipeHold(saved, bootId = { boot }, nowMs = { 1_000L }) { reported++ }
        val reapply = applier(device, after).reapplyPersisted()!!

        assertEquals(ConfigOutcome.APPLIED, reapply.outcomes["policies.factoryReset"])
        assertTrue(KEY in device.restrictions)
        assertEquals("a reboot is not wipeNotStarted", 0, reported)
    }

    @Test
    fun `policy apply after an accepted wipe defers the block too`() = runTest {
        val device = Device(blocked = true)
        val hold = FactoryResetWipeHold()
        wipeHandler(device, hold, applier(device, hold)).handle(wipeCommand)

        val result = PolicyApplyHandler(toggles(device, hold)::current).handle(
            CommandEnvelope(
                commandId = "p1",
                issuedAt = "2026-01-01T00:00:00Z",
                type = "policy.apply",
                payload = buildJsonObject {
                    put("policy", "factoryReset")
                    put("value", false)
                },
            ),
        )

        assertEquals(CommandStatus.DONE, result.status)
        assertEquals(ConfigOutcome.deferred(WipeHeldFactoryResetPolicy.WIPING), result.detail)
        assertFalse(KEY in device.restrictions)
    }

    /** Had the process died before the wipe call, the stale revision makes the server re-send the configuration. */
    @Test
    fun `the configuration is reported stale once the block is lifted`() = runTest {
        val device = Device(blocked = true)
        val hold = FactoryResetWipeHold()
        val applier = applier(device, hold)
        applier.apply(block)
        assertEquals("r1", store.revision())

        wipeHandler(device, hold, applier).handle(wipeCommand)

        assertEquals(ConfigStateStore.STALE_REVISION, store.revision())
    }

    @Test
    fun `a wipe that throws restores the block and later applies set it again`() = runTest {
        val device = Device(blocked = true, wipeError = SecurityException("wipe refused"))
        val hold = FactoryResetWipeHold()
        val applier = applier(device, hold)

        val wiped = wipeHandler(device, hold, applier).handle(wipeCommand)

        assertEquals(CommandStatus.FAILED, wiped.status)
        assertTrue(KEY in device.restrictions)
        assertFalse(hold.held)

        device.restrictions.clear()
        assertEquals(ConfigOutcome.APPLIED, applier.apply(block).outcomes["policies.factoryReset"])
        assertTrue(KEY in device.restrictions)
    }

    private fun dispatcher(device: Device, hold: FactoryResetWipeHold, applier: ConfigApplier) =
        CommandDispatcher(
            listOf(wipeHandler(device, hold, applier), ConfigApplyHandler(applier)),
            wiping = { hold.held },
        )

    private fun configApply(id: String, doc: ConfigApplyPayload) = CommandEnvelope(
        commandId = id,
        issuedAt = "2026-01-01T00:00:00Z",
        type = "config.apply",
        payload = ProtocolJson.json.encodeToJsonElement(ConfigApplyPayload.serializer(), doc).jsonObject,
    )

    private companion object {
        const val KEY = UserRestrictions.DISALLOW_FACTORY_RESET
        val ALLOW = mapOf("factoryReset" to true)
        const val FAIL = "fail"
    }
}
