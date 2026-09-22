package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.ProtocolJson

/**
 * `kiosk.enter` — put the device into COSU lock-task. Payload: [KioskApplyPayload]
 * (`mode`, `allowedPackages`, `pinPackage`, `features`, `exitMode`, `password`, `theme`). The
 * agent's own package is always allowlisted; the applier claims the agent's kiosk launcher as
 * the persistent HOME. On success the payload is persisted so the launcher can
 * render it and the agent can re-enter on boot.
 */
class KioskEnterHandler(private val applier: KioskApplier) : CommandHandler {
    override val type: String = "kiosk.enter"
    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val p = command.payload?.let {
            runCatching { ProtocolJson.json.decodeFromJsonElement(KioskApplyPayload.serializer(), it) }
                .getOrElse { e -> return CommandResults.failed(command, "bad payload: ${e.message}") }
        } ?: KioskApplyPayload()
        return when (val r = applier.enter(p)) {
            KioskResult.Ok -> CommandResults.done(command)
            KioskResult.Unsupported -> CommandResults.unsupported(command, "kiosk requires Device Owner")
            is KioskResult.Failed -> CommandResults.failed(command, r.reason)
        }
    }
}
