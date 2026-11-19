package com.mdmesh.core.config

import android.content.ComponentName
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
import com.mdmesh.policy.factoryreset.WipeHeldFactoryResetPolicy
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigOutcome
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
    private class Device(blocked: Boolean, private val wipeError: Exception? = null) : WipeTarget {
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
        override fun wipe() {
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
        return ConfigApplier(toggles(device, hold)::current, kiosk, {}, store, setPowerMode = {})
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

    private companion object {
        const val KEY = UserRestrictions.DISALLOW_FACTORY_RESET
    }
}
