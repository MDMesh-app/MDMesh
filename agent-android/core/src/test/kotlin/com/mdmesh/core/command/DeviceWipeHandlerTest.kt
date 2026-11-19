package com.mdmesh.core.command

import com.mdmesh.core.command.handlers.DeviceWipeHandler
import com.mdmesh.core.command.handlers.WipeRestrictions
import com.mdmesh.core.command.handlers.WipeTarget
import com.mdmesh.policy.UserRestrictions
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
            calls += "clear $key"
            restrictions -= key
        }
        override fun wipe() {
            calls += "wipe blocked=${hasRestriction(KEY)}"
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

    @Test
    fun `does not wipe and fails clearly when ours and another source both block`() = runTest {
        val device = FakeDevice(blocked = true, blockedElsewhere = true)

        val result = wipe(device)

        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals(
            "Another admin or source blocks factory reset on the device ($KEY), so the wipe was not started",
            result.detail,
        )
        // Ours is put back, so the device ends up exactly as configured; no wipe was started.
        assertEquals(listOf("clear $KEY", "add $KEY"), device.calls)
        assertTrue(KEY in device.restrictions)
    }

    @Test
    fun `touches nothing and fails clearly when only another source blocks`() = runTest {
        val device = FakeDevice(blocked = false, blockedElsewhere = true)

        val result = wipe(device)

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(result.detail.orEmpty().contains("Another admin or source blocks factory reset"))
        // Never cleared, so never restored: no block of ours is added that the configuration didn't set.
        assertTrue(device.calls.isEmpty())
        assertFalse(KEY in device.restrictions)
    }

    @Test
    fun `says so when our block can't be put back after another source refuses the wipe`() = runTest {
        val device = FakeDevice(blocked = true, blockedElsewhere = true, addFails = true)

        val result = wipe(device)

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(result.detail.orEmpty().endsWith("so the wipe was not started; could not restore $KEY"))
    }

    @Test
    fun `says so when our block can't be put back after the wipe call throws`() = runTest {
        val device = FakeDevice(blocked = true, wipeError = SecurityException("wipe refused"), addFails = true)

        val result = wipe(device)

        assertEquals("wipe refused; could not restore $KEY", result.detail)
    }

    @Test
    fun `restores the block and fails when checking it throws after the clear`() = runTest {
        val device = FakeDevice(blocked = true, checkFailsAfterClear = true)

        val result = wipe(device)

        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals("user manager gone", result.detail)
        assertEquals(listOf("clear $KEY", "add $KEY"), device.calls)
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
