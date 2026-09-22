package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult

/**
 * `kiosk.exit` — release COSU lock-task (clear allowlist + persistent-HOME claim). On success the
 * persisted kiosk payload is cleared (see KioskApplier) so the agent does not re-enter kiosk on next boot,
 * and the launcher is brought forward so it unpins and drops to its idle screen immediately.
 */
class KioskExitHandler(private val applier: KioskApplier) : CommandHandler {
    override val type: String = "kiosk.exit"
    override suspend fun handle(command: CommandEnvelope): CommandResult = when (val r = applier.exit()) {
        KioskResult.Ok -> CommandResults.done(command)
        KioskResult.Unsupported -> CommandResults.unsupported(command, "kiosk unsupported on this device")
        is KioskResult.Failed -> CommandResults.failed(command, r.reason)
    }
}
