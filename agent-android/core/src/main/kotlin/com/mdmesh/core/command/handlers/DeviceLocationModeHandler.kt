package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.location.LocationModeStore
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable

/** `device.locationMode` — switch location capture between battery-saving "passive" and "active". */
class DeviceLocationModeHandler(
    private val store: LocationModeStore,
) : CommandHandler {

    override val type: String = DeviceAction.LOCATION_MODE

    @Serializable
    private data class Payload(val mode: String)

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        val p = command.payload?.let { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }
            ?: return CommandResults.failed(command, "device.locationMode requires { mode }")
        store.set(p.mode)
        CommandResults.done(command, "location mode = ${store.get()}")
    }.getOrElse { CommandResults.failed(command, it.message ?: "location mode failed") }
}
