package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.device.AppInventoryCollector
import com.mdmesh.proto.AppIconsRequest
import com.mdmesh.proto.AppIconsResult
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson

/**
 * `apps.icons` — render a batch of packages' launcher icons as base64 PNGs. Payload is an
 * [AppIconsRequest] (`{ packages: [...] }`); the result ([AppIconsResult]) is returned as JSON in
 * the command-result `detail`. The console requests icons in small chunks and caches them locally,
 * so this stays well within a reasonable result size.
 */
class AppIconsHandler(
    private val inventory: AppInventoryCollector,
) : CommandHandler {

    override val type: String = "apps.icons"

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val req = command.payload?.let {
            runCatching { ProtocolJson.json.decodeFromJsonElement(AppIconsRequest.serializer(), it) }
                .getOrElse { e -> return CommandResults.failed(command, "bad payload: ${e.message}") }
        } ?: AppIconsRequest()

        if (req.packages.isEmpty()) {
            return CommandResults.done(command, ProtocolJson.json.encodeToString(AppIconsResult.serializer(), AppIconsResult()))
        }
        val result = runCatching { AppIconsResult(inventory.icons(req.packages)) }
            .getOrElse { return CommandResults.failed(command, "icons failed: ${it.message}") }
        val json = ProtocolJson.json.encodeToString(AppIconsResult.serializer(), result)
        return CommandResults.done(command, json)
    }
}
