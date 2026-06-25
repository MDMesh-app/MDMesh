package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.power.PowerModeStore
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable

/** `device.powerMode` — switch connectivity between battery-saving "adaptive" and "alwaysOn". */
class DevicePowerModeHandler(
    private val store: PowerModeStore,
) : CommandHandler {

    override val type: String = DeviceAction.POWER_MODE

    @Serializable
    private data class Payload(val mode: String)

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        val p = command.payload?.let { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }
            ?: return CommandResults.failed(command, "device.powerMode requires { mode }")
        store.set(p.mode)
        // The foreground service re-evaluates the socket on its next cycle / screen-power event.
        CommandResults.done(command, "power mode = ${store.get()}")
    }.getOrElse { CommandResults.failed(command, it.message ?: "power mode failed") }
}
