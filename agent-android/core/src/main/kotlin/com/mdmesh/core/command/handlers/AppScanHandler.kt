package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.device.AppInventoryCollector
import com.mdmesh.proto.AppScanResult
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson

/**
 * `apps.scan` — enumerate installed packages (metadata only) for the console's kiosk app picker.
 * The list is returned as JSON ([AppScanResult]) in the command-result `detail`, so no new endpoint
 * or storage is needed: the console reads it back from command history. Icons come separately via
 * `apps.icons` ([AppIconsHandler]).
 */
class AppScanHandler(
    private val inventory: AppInventoryCollector,
) : CommandHandler {

    override val type: String = "apps.scan"

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val result = runCatching { AppScanResult(inventory.scan()) }
            .getOrElse { return CommandResults.failed(command, "scan failed: ${it.message}") }
        val json = ProtocolJson.json.encodeToString(AppScanResult.serializer(), result)
        return CommandResults.done(command, json)
    }
}
