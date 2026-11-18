package com.mdmesh.core.command

import com.mdmesh.core.command.handlers.KioskClearPinLockoutHandler
import com.mdmesh.kiosk.ExitPinGate
import com.mdmesh.kiosk.InMemoryPinAttemptStore
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import com.mdmesh.proto.DeviceAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The console's Clear PIN lockout: clears the gate the launcher reads, and says what it cleared. */
class KioskClearPinLockoutHandlerTest {

    private val pin = "482139"
    private var uptimeMs = 3_600_000L
    private val store = InMemoryPinAttemptStore()
    private val gate = ExitPinGate(store) { uptimeMs }
    private val handler = KioskClearPinLockoutHandler(gate)

    private val command = CommandEnvelope(
        commandId = "c1",
        issuedAt = "2026-01-01T00:00:00Z",
        type = DeviceAction.KIOSK_CLEAR_PIN_LOCKOUT,
    )

    private fun lockOut() {
        while (gate.attempt("000000", pin) !is ExitPinGate.Outcome.Locked) Unit
    }

    @Test
    fun `handles the advertised command type`() {
        assertEquals("kiosk.clearPinLockout", handler.type)
        assertTrue(DeviceAction.ADVERTISED_KEYS.contains(DeviceAction.KIOSK_CLEAR_PIN_LOCKOUT_KEY))
    }

    @Test
    fun `ends a lockout, and the exit takes the PIN again at once`() = runTest {
        lockOut()
        val result = handler.handle(command)
        assertEquals(CommandStatus.DONE, result.status)
        assertEquals("exit lockout cleared (5 wrong PINs)", result.detail)
        assertNull(gate.remainingLockMs(pin))
        assertEquals(ExitPinGate.Outcome.Open, gate.attempt(pin, pin))
    }

    @Test
    fun `is done with nothing to clear, and when sent twice`() = runTest {
        assertEquals("no wrong PINs to clear", handler.handle(command).detail)
        gate.attempt("000000", pin)
        assertEquals("wrong-PIN count cleared (1 wrong PINs)", handler.handle(command).detail)
        assertEquals(CommandStatus.DONE, handler.handle(command).status)
    }

    @Test
    fun `fails, keeping the lockout, when the clear can't be saved`() = runTest {
        lockOut()
        store.writable = false
        val result = handler.handle(command)
        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(gate.remainingLockMs(pin) != null)
    }
}
