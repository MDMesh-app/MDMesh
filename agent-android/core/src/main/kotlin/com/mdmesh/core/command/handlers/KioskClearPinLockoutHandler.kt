package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.kiosk.ExitPinGate
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction

/**
 * `kiosk.clearPinLockout`: the console's Clear PIN lockout. Clears the exit PIN's wrong-PIN count and any lockout
 * ([ExitPinGate.clear]), so staff can try the PIN again at once with a full first round of tries. The device stays in
 * kiosk. An open Exit locked dialog closes on its next tick, which reads the gate. Idempotent: with nothing to clear
 * it is still done. Synchronized with the PIN check inside the gate, so a clear racing a PIN being typed is applied
 * either before that attempt or after it, never lost.
 */
class KioskClearPinLockoutHandler(private val gate: ExitPinGate) : CommandHandler {
    override val type: String = DeviceAction.KIOSK_CLEAR_PIN_LOCKOUT

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val cleared = gate.clear() ?: return CommandResults.failed(command, "could not save the cleared count")
        return CommandResults.done(command, detail(cleared))
    }

    internal companion object {
        fun detail(cleared: ExitPinGate.Cleared): String = when {
            cleared.wasLocked -> "exit lockout cleared (${cleared.failures} wrong PINs)"
            cleared.failures > 0 -> "wrong-PIN count cleared (${cleared.failures} wrong PINs)"
            else -> "no wrong PINs to clear"
        }
    }
}
