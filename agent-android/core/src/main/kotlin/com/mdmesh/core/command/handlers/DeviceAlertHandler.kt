package com.mdmesh.core.command.handlers

import com.mdmesh.core.action.AlertNotifier
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable

/** `device.alert` — show a high-priority message to the device user. */
class DeviceAlertHandler(
    private val notifier: AlertNotifier,
) : CommandHandler {

    override val type: String = DeviceAction.ALERT

    @Serializable
    private data class Payload(val title: String? = null, val body: String)

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        val p = command.payload?.let { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }
            ?: return CommandResults.failed(command, "device.alert requires a payload")
        notifier.show(p.title ?: "Message from IT", p.body)
        CommandResults.done(command)
    }.getOrElse { CommandResults.failed(command, it.message ?: "alert failed") }
}
