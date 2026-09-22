package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.config.ConfigApplier
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigApplyResult
import com.mdmesh.proto.DeviceAction
import com.mdmesh.proto.ProtocolJson

/**
 * `config.apply` — desired-state push. Decodes the document, delegates to [ConfigApplier], and reports the
 * per-key outcomes as JSON in `detail`. `done` = every key applied/unsupported (revision now persisted and
 * reported in check-ins); `failed` = at least one key failed (server retries after a backoff).
 */
class ConfigApplyHandler(private val applier: ConfigApplier) : CommandHandler {
    override val type: String = DeviceAction.CONFIG_APPLY

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = command.payload ?: return CommandResults.failed(command, "config.apply requires a payload")
        val doc = runCatching { ProtocolJson.json.decodeFromJsonElement(ConfigApplyPayload.serializer(), payload) }
            .getOrElse { return CommandResults.failed(command, "bad payload: ${it.message}") }
        if (doc.revision.isBlank()) return CommandResults.failed(command, "config.apply requires a revision")
        val result = applier.apply(doc)
        val detail = ProtocolJson.json.encodeToString(ConfigApplyResult.serializer(), result)
        return if (ConfigApplier.succeeded(result)) CommandResults.done(command, detail) else CommandResults.failed(command, detail)
    }
}
