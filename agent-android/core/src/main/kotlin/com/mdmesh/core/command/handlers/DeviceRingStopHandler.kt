package com.mdmesh.core.command.handlers

import com.mdmesh.core.action.RingController
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction

/** `device.ringStop` — silence an active locate tone. */
class DeviceRingStopHandler(
    private val ring: RingController,
) : CommandHandler {

    override val type: String = DeviceAction.RING_STOP

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        ring.stop()
        CommandResults.done(command)
    }.getOrElse { CommandResults.failed(command, it.message ?: "ring stop failed") }
}
