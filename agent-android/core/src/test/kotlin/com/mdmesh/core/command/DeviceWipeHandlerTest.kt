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
        blocked: Boolean = false,
        /** Another source (another admin, a vendor default) also sets the block, so clearing ours doesn't lift it. */
        private val blockedElsewhere: Boolean = false,
        private val wipeError: Exception? = null,
        /** The restriction check throws once something was cleared (a dying system service). */
        private val checkFailsAfterClear: Boolean = false,
    ) : WipeTarget {
        val restrictions = if (blocked) mutableSetOf(KEY) else mutableSetOf()
        val calls = mutableListOf<String>()

        override fun isDeviceOwner() = deviceOwner
        override fun hasRestriction(key: String): Boolean {
            check(!(checkFailsAfterClear && calls.any { it.startsWith("clear") })) { "user manager gone" }
            return key in restrictions || (blockedElsewhere && key == KEY)
        }
        override fun addRestriction(key: String) {
            calls += "add $key"
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
    fun `does not wipe and fails clearly when the block can't be lifted`() = runTest {
        val device = FakeDevice(blocked = true, blockedElsewhere = true)

        val result = wipe(device)

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(result.detail.orEmpty().contains("the wipe was not started"))
        // Ours is put back, so the device ends up exactly as configured; no wipe was started.
        assertEquals(listOf("clear $KEY", "add $KEY"), device.calls)
        assertTrue(KEY in device.restrictions)
    }

    @Test
    fun `restores the block and fails when checking it throws after the clear`() = runTest {
        val device = FakeDevice(blocked = true, checkFailsAfterClear = true)

        val result = wipe(device)

        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals(listOf("clear $KEY", "add $KEY"), device.calls)
    }

    @Test
    fun `lifts only the factory reset block, never the kiosk's safe boot block`() {
        assertEquals(listOf(KEY), WipeRestrictions.keys)
        assertFalse(UserRestrictions.DISALLOW_SAFE_BOOT in WipeRestrictions.keys)
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
