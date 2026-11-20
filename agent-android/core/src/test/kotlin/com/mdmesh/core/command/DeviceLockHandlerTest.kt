package com.mdmesh.core.command

import com.mdmesh.core.command.handlers.DeviceLockHandler
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceLockHandlerTest {

    private val command = CommandEnvelope(
        commandId = "c1",
        issuedAt = "2026-01-01T00:00:00Z",
        type = "device.lock",
    )

    @Test
    fun `locks and reports a plain done on a device with a screen lock`() = runTest {
        var locked = 0
        val result = DeviceLockHandler(lockNow = { locked++ }, isDeviceSecure = { true }).handle(command)

        assertEquals(1, locked)
        assertEquals(CommandStatus.DONE, result.status)
        assertNull(result.detail)
    }

    @Test
    fun `still locks without a screen lock but says the device isn't protected`() = runTest {
        var locked = 0
        val result = DeviceLockHandler(lockNow = { locked++ }, isDeviceSecure = { false }).handle(command)

        assertEquals(1, locked)
        assertEquals(CommandStatus.DONE, result.status)
        assertEquals(
            "No screen lock is set: the screen turned off, but the device isn't protected.",
            result.detail,
        )
    }

    @Test
    fun `reports a plain done when the screen lock can't be read`() = runTest {
        val result = DeviceLockHandler(lockNow = {}, isDeviceSecure = { error("keyguard gone") }).handle(command)

        assertEquals(CommandStatus.DONE, result.status)
        assertNull(result.detail)
    }

    @Test
    fun `fails when the lock call throws`() = runTest {
        val result = DeviceLockHandler(lockNow = { throw SecurityException("not admin") }, isDeviceSecure = { true })
            .handle(command)

        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals("not admin", result.detail)
    }
}
