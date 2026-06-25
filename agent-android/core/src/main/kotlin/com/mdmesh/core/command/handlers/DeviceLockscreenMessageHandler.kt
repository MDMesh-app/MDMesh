package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable

/** `device.lockscreenMessage` — set/clear the Device-Owner lock-screen info string. */
class DeviceLockscreenMessageHandler(
    private val handle: DpmHandle,
) : CommandHandler {

    override val type: String = DeviceAction.LOCKSCREEN_MESSAGE

    @Serializable
    private data class Payload(val message: String? = null)

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        val msg = command.payload
            ?.let { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }
            ?.message
        handle.dpm.setDeviceOwnerLockScreenInfo(handle.admin, msg?.ifBlank { null })
        CommandResults.done(command)
    }.getOrElse { CommandResults.failed(command, it.message ?: "lockscreen message failed") }
}
