package com.mdmesh.core.command.handlers

import com.mdmesh.core.action.AppLauncher
import com.mdmesh.core.action.LaunchOutcome
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `app.launch` — open an app on the device, as if its icon were tapped. Payload: `{ packageName, activity? }`.
 * Unlike a kiosk pin, nothing keeps the app open: the user leaves it as usual. Gated on the `app.launch` capability.
 */
class AppLaunchHandler(
    private val launcher: AppLauncher,
) : CommandHandler {

    override val type: String = TYPE

    @Serializable
    private data class Payload(val packageName: String, val activity: String? = null)

    /** The payload, or why it can't be used. */
    private sealed interface Parsed {
        data class Ok(val payload: Payload) : Parsed

        data class Bad(val reason: String) : Parsed
    }

    override suspend fun handle(command: CommandEnvelope): CommandResult =
        when (val parsed = parse(command.payload)) {
            is Parsed.Bad -> CommandResults.failed(command, parsed.reason)
            is Parsed.Ok -> open(command, parsed.payload)
        }

    private fun parse(payload: JsonElement?): Parsed =
        if (payload == null) {
            Parsed.Bad("app.launch requires a payload { packageName, activity? }")
        } else {
            runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), payload) }
                .fold(
                    onSuccess = { p ->
                        if (PACKAGE.matches(p.packageName)) {
                            Parsed.Ok(p)
                        } else {
                            Parsed.Bad("not a package name: ${p.packageName}")
                        }
                    },
                    onFailure = { Parsed.Bad("bad payload: ${it.message}") },
                )
        }

    private fun open(command: CommandEnvelope, p: Payload): CommandResult =
        when (val outcome = launcher.launch(p.packageName, p.activity?.takeIf { it.isNotBlank() })) {
            LaunchOutcome.Launched -> CommandResults.done(command)
            is LaunchOutcome.Refused -> CommandResults.failed(command, outcome.reason)
        }

    companion object {
        const val TYPE = "app.launch"
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}
