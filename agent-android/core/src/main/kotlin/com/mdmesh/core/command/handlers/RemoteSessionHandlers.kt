@file:Suppress("MaxLineLength", "ReturnCount")

package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson
import com.mdmesh.proto.RemoteSessionMode
import com.mdmesh.proto.RemoteStartSessionPayload
import com.mdmesh.proto.RemoteStopSessionPayload
import com.mdmesh.remote.RemoteControlSession
import java.time.Instant

/** View-only remote-session contract handlers. Capture, signaling, and media are deliberately deferred. */
class RemoteStartSessionHandler(
    private val session: RemoteControlSession,
    private val now: () -> Instant = Instant::now,
) : CommandHandler {
    override val type: String = "remote.startSession"

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = command.payload ?: return CommandResults.failed(command, "remote.startSession requires a payload")
        val request = runCatching { ProtocolJson.json.decodeFromJsonElement(RemoteStartSessionPayload.serializer(), payload) }
            .getOrElse { return CommandResults.failed(command, "bad payload: ${it.message}") }
        if (request.sessionId.isBlank() || request.signaling.endpoint.isBlank() || request.signaling.credential.isBlank()) {
            return CommandResults.failed(command, "remote.startSession requires sessionId and signaling credentials")
        }
        val expiry = runCatching { Instant.parse(request.expiresAt) }
            .getOrElse { return CommandResults.failed(command, "remote.startSession requires an ISO-8601 expiresAt") }
        if (!expiry.isAfter(now())) return CommandResults.expired(command, "remote session expired")
        if (request.mode != RemoteSessionMode.VIEW) {
            return CommandResults.unsupported(command, "remote control is not implemented")
        }
        return session.start(request).fold(
            onSuccess = { CommandResults.accepted(command, "awaiting device consent") },
            onFailure = { CommandResults.failed(command, it.message ?: "remote session could not start") },
        )
    }
}

class RemoteStopSessionHandler(private val session: RemoteControlSession) : CommandHandler {
    override val type: String = "remote.stopSession"

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = command.payload ?: return CommandResults.failed(command, "remote.stopSession requires a payload")
        val request = runCatching { ProtocolJson.json.decodeFromJsonElement(RemoteStopSessionPayload.serializer(), payload) }
            .getOrElse { return CommandResults.failed(command, "bad payload: ${it.message}") }
        if (request.sessionId.isBlank()) return CommandResults.failed(command, "remote.stopSession requires a sessionId")
        return session.stop(request.sessionId, request.reason).fold(
            onSuccess = { CommandResults.done(command, "remote session stopped") },
            onFailure = { CommandResults.failed(command, it.message ?: "remote session could not stop") },
        )
    }
}
