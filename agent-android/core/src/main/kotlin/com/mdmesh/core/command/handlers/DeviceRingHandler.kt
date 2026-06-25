package com.mdmesh.core.command.handlers

import com.mdmesh.core.action.RingController
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable

/** `device.ring` — play a loud locate tone for `durationMs` (default 30s). */
class DeviceRingHandler(
    private val ring: RingController,
) : CommandHandler {

    override val type: String = DeviceAction.RING

    @Serializable
    private data class Payload(val durationMs: Long? = null)

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        val dur = command.payload
            ?.let { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }
            ?.durationMs ?: 30_000L
        ring.start(dur)
        CommandResults.done(command)
    }.getOrElse { CommandResults.failed(command, it.message ?: "ring failed") }
}
