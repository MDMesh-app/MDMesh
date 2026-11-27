package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.install.InstallManager
import com.mdmesh.core.install.InstallOutcome
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * `app.uninstall` — silently uninstall a package as Device Owner. Payload: `{ packageName }`.
 *
 * An app of the kiosk the device is in can't be uninstalled ([inKiosk]: the kiosk guard blocks it); the admin takes
 * it out of the kiosk first, which lifts the block.
 */
class AppUninstallHandler(
    private val installManager: InstallManager,
    private val inKiosk: (packageName: String) -> Boolean = { false },
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

        if (inKiosk(p.packageName)) return CommandResults.failed(command, IN_KIOSK)
        return when (val outcome = installManager.uninstall(p.packageName)) {
            InstallOutcome.Success -> CommandResults.done(command)
            is InstallOutcome.Skipped -> CommandResults.done(command, "skipped: ${outcome.reason}")
            is InstallOutcome.Failure -> CommandResults.failed(command, outcome.reason)
        }
    }

    companion object {
        const val IN_KIOSK = "This app is part of the device's kiosk. Take it out of the kiosk first."
    }
}
