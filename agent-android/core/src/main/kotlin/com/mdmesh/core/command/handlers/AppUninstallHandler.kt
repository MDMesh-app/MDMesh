package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.apps.AppActionGuard
import com.mdmesh.core.install.InstallOutcome
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable

/** `app.uninstall` — silently uninstall a package as Device Owner. Payload: `{ packageName }`. */
class AppUninstallHandler(
    private val uninstall: suspend (String) -> InstallOutcome,
    private val guard: AppActionGuard,
) : CommandHandler {

    override val type: String = "app.uninstall"

    @Serializable
    private data class Payload(val packageName: String)

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = command.payload
            ?: return CommandResults.failed(command, "app.uninstall requires a payload")
        val p = runCatching {
            ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), payload)
        }.getOrElse { return CommandResults.failed(command, "bad payload: ${it.message}") }

        val reason = runCatching { guard.reason(p.packageName, uninstall = true) }
            .getOrElse { it.message ?: "Package protection check failed" }
        if (reason != null) return CommandResults.failed(command, reason)
        return when (val outcome = uninstall(p.packageName)) {
            InstallOutcome.Success -> CommandResults.done(command)
            is InstallOutcome.Skipped -> CommandResults.done(command, "skipped: ${outcome.reason}")
            is InstallOutcome.Failure -> CommandResults.failed(command, outcome.reason)
        }
    }
}
