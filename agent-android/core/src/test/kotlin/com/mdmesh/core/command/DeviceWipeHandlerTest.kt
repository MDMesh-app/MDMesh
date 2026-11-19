package com.mdmesh.core.command

import com.mdmesh.core.command.handlers.DeviceWipeHandler
import com.mdmesh.core.command.handlers.WipeRestrictions
import com.mdmesh.core.command.handlers.WipeTarget
import com.mdmesh.policy.UserRestrictions
import com.mdmesh.policy.factoryreset.FactoryResetWipeHold
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.CommandStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub #222: a vendor recovery (HyperOS) refused to format data while Factory reset: Block was set, leaving the
 * device stuck in recovery. The wipe lifts the block first and puts it back if the wipe call fails.
 */
class DeviceWipeHandlerTest {

    private class FakeDevice(
        var deviceOwner: Boolean = true,
        /** Our admin sets the block. */
        blocked: Boolean = false,
        /** Another source (another admin, a vendor default) sets the block, so clearing ours doesn't lift it. */
        private val blockedElsewhere: Boolean = false,
        private val wipeError: Exception? = null,
        /** The restriction check throws once something was cleared (a dying system service). */
        private val checkFailsAfterClear: Boolean = false,
        /** Putting a restriction back throws. */
        private val addFails: Boolean = false,
    ) : WipeTarget {
        /** Ours only. */
        val restrictions = if (blocked) mutableSetOf(KEY) else mutableSetOf()
        val calls = mutableListOf<String>()
        var onClear: () -> Unit = {}

        override fun isDeviceOwner() = deviceOwner
        override fun holdsRestriction(key: String) = key in restrictions
        override fun hasRestriction(key: String): Boolean {
            check(!(checkFailsAfterClear && calls.any { it.startsWith("clear") })) { "user manager gone" }
            return key in restrictions || (blockedElsewhere && key == KEY)
        }
        override fun addRestriction(key: String) {
            calls += "add $key"
            check(!addFails) { "add refused" }
            restrictions += key
        }
        override fun clearRestriction(key: String) {
            onClear()
            calls += "clear $key"
            restrictions -= key
        }
        override fun wipe() {
            // Not through hasRestriction: that one may be set to throw.
            calls += "wipe blocked=${KEY in restrictions || blockedElsewhere}"
            wipeError?.let { throw it }
        }
    }

    private val command = CommandEnvelope(
        commandId = "c1",
        issuedAt = "2026-01-01T00:00:00Z",
        type = "device.wipe",
    )

    private suspend fun wipe(device: FakeDevice): CommandResult = DeviceWipeHandler(device).handle(command)

    @Test
    fun `lifts Factory reset Block before wiping`() = runTest {
        val device = FakeDevice(blocked = true)

        val result = wipe(device)

        assertEquals(CommandStatus.DONE, result.status)
        assertEquals(listOf("clear $KEY", "wipe blocked=false"), device.calls)
    }

    @Test
    fun `restores Factory reset Block when the wipe call throws`() = runTest {
        val device = FakeDevice(blocked = true, wipeError = SecurityException("wipe refused"))

        val result = wipe(device)

        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals("wipe refused", result.detail)
        assertEquals(listOf("clear $KEY", "wipe blocked=false", "add $KEY"), device.calls)
        assertTrue(KEY in device.restrictions)
    }

    @Test
    fun `leaves the restrictions alone when Factory reset is not blocked`() = runTest {
        val device = FakeDevice(blocked = false, wipeError = IllegalStateException("boom"))

        wipe(device)

        assertEquals(listOf("wipe blocked=false"), device.calls)
        assertFalse(KEY in device.restrictions)
    }

    /** Stock Android wipes past another admin's block; refusing would stop wiping a lost device. */
    @Test
    fun `lifts ours and still wipes when another source also blocks, and says so`() = runTest {
        val device = FakeDevice(blocked = true, blockedElsewhere = true)

        val result = wipe(device)

        assertEquals(CommandStatus.DONE, result.status)
        assertEquals(
            "another admin or source also blocks factory reset ($KEY); some vendor recoveries may refuse it",
            result.detail,
        )
        assertEquals(listOf("clear $KEY", "wipe blocked=true"), device.calls)
    }

    @Test
    fun `wipes without touching our restrictions when only another source blocks`() = runTest {
        val device = FakeDevice(blocked = false, blockedElsewhere = true)

        val result = wipe(device)

        assertEquals(CommandStatus.DONE, result.status)
        assertTrue(result.detail.orEmpty().startsWith("another admin or source also blocks factory reset"))
        // Never cleared, so never restored: no block of ours is added that the configuration didn't set.
        assertEquals(listOf("wipe blocked=true"), device.calls)
        assertFalse(KEY in device.restrictions)
    }

    /**
     * PR #227 review: a lost device is always wiped. A check that throws after ours was cleared doesn't stop the wipe,
     * and ours stays off for it: putting it back first is what stopped HyperOS recovery (#222).
     */
    @Test
    fun `still wipes with ours lifted when checking the block throws, and says so`() = runTest {
        val device = FakeDevice(blocked = true, checkFailsAfterClear = true)

        val result = wipe(device)

        assertEquals(CommandStatus.DONE, result.status)
        assertEquals("could not lift the factory reset block (user manager gone); wiped anyway", result.detail)
        assertEquals(listOf("clear $KEY", "wipe blocked=false"), device.calls)
    }

    @Test
    fun `still wipes when clearing the block throws`() = runTest {
        val device = FakeDevice(blocked = true).apply { onClear = { throw SecurityException("clear refused") } }

        val result = wipe(device)

        assertEquals(CommandStatus.DONE, result.status)
        assertEquals("could not lift the factory reset block (clear refused); wiped anyway", result.detail)
        assertEquals(listOf("wipe blocked=true"), device.calls)
    }

    @Test
    fun `reports the lift error, the wipe error and what it could not put back`() = runTest {
        val device = FakeDevice(
            blocked = true,
            checkFailsAfterClear = true,
            addFails = true,
            wipeError = SecurityException("wipe refused"),
        )

        val result = wipe(device)

        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals(
            "wipe refused; could not lift the factory reset block (user manager gone); could not restore $KEY",
            result.detail,
        )
        assertEquals(listOf("clear $KEY", "wipe blocked=false", "add $KEY"), device.calls)
    }

    /** No apply may put the block back once it is lifted, even if the process dies before the wipe call. */
    @Test
    fun `holds before lifting the block`() = runTest {
        val hold = FactoryResetWipeHold()
        val heldAtClear = mutableListOf<Boolean>()
        val device = FakeDevice(blocked = true).apply { onClear = { heldAtClear += hold.held } }

        DeviceWipeHandler(device, hold).handle(command)

        assertEquals(listOf(true), heldAtClear)
        assertTrue(hold.held)
    }

    @Test
    fun `keeps the hold when the lift check fails and the wipe goes ahead`() = runTest {
        val hold = FactoryResetWipeHold()

        DeviceWipeHandler(FakeDevice(blocked = true, checkFailsAfterClear = true), hold).handle(command)

        assertTrue(hold.held)
    }

    @Test
    fun `releases the hold when the wipe call throws`() = runTest {
        val hold = FactoryResetWipeHold()

        DeviceWipeHandler(FakeDevice(blocked = true, wipeError = SecurityException("no")), hold).handle(command)

        assertFalse(hold.held)
    }

    @Test
    fun `says so when our block can't be put back after the wipe call throws`() = runTest {
        val device = FakeDevice(blocked = true, wipeError = SecurityException("wipe refused"), addFails = true)

        val result = wipe(device)

        assertEquals("wipe refused; could not restore $KEY", result.detail)
    }

    @Test
    fun `restores the block when checking it throws after the clear and the wipe throws too`() = runTest {
        val device = FakeDevice(blocked = true, checkFailsAfterClear = true, wipeError = SecurityException("no"))

        val result = wipe(device)

        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals(listOf("clear $KEY", "wipe blocked=false", "add $KEY"), device.calls)
        assertTrue(KEY in device.restrictions)
    }

    @Test
    fun `lifts only the factory reset block, never the kiosk's safe boot block`() {
        assertEquals(listOf(KEY), WipeRestrictions.keys)
        assertFalse(UserRestrictions.DISALLOW_SAFE_BOOT in WipeRestrictions.keys)
    }

    /** If the process dies between the lift and the wipe call, the server must re-send the configuration. */
    @Test
    fun `marks the configuration stale before lifting the block`() = runTest {
        val device = FakeDevice(blocked = true)

        DeviceWipeHandler(device, markConfigStale = { device.calls += "stale" }).handle(command)

        assertEquals(listOf("stale", "clear $KEY", "wipe blocked=false"), device.calls)
    }

    @Test
    fun `leaves the configuration alone when nothing is lifted`() = runTest {
        val device = FakeDevice(blocked = false)

        DeviceWipeHandler(device, markConfigStale = { device.calls += "stale" }).handle(command)

        assertEquals(listOf("wipe blocked=false"), device.calls)
    }

    @Test
    fun `is unsupported without Device Owner`() = runTest {
        val device = FakeDevice(deviceOwner = false, blocked = true)

        val result = wipe(device)

        assertEquals(CommandStatus.UNSUPPORTED, result.status)
        assertTrue(device.calls.isEmpty())
    }

    @Test
    fun `runs the lift and the wipe as one exclusive step`() = runTest {
        val device = FakeDevice(blocked = true)
        val trace = mutableListOf<String>()
        val handler = DeviceWipeHandler(device) { block ->
            trace += "lock"
            block().also { trace += "unlock after ${device.calls}" }
        }

        handler.handle(command)

        assertEquals(listOf("lock", "unlock after [clear $KEY, wipe blocked=false]"), trace)
    }

    private companion object {
        const val KEY = UserRestrictions.DISALLOW_FACTORY_RESET
    }
}
