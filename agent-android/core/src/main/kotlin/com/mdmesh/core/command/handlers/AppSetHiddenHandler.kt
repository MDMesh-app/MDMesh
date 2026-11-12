package com.mdmesh.core.command.handlers

import com.mdmesh.core.apps.AppActionGuard
import com.mdmesh.core.apps.AppVisibility
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable

/** `app.setHidden`: disable by hiding, or enable again, without removing app data. */
class AppSetHiddenHandler(private val visibility: AppVisibility, private val guard: AppActionGuard) : CommandHandler {
    override val type: String = "app.setHidden"

    @Serializable
    private data class Payload(val packageName: String, val hidden: Boolean)

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        val p = ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), requireNotNull(command.payload))
        val reason = if (p.hidden) {
            guard.reason(p.packageName, uninstall = false)
        } else {
            guard.reason(p.packageName, emptySet())
        }
        when {
            reason != null -> CommandResults.failed(command, reason)
            !visibility.isDeviceOwner() -> CommandResults.unsupported(command, "Device Owner required")
            visibility.isHidden(p.packageName) == p.hidden -> CommandResults.done(command)
            !visibility.setHidden(p.packageName, p.hidden) || visibility.isHidden(p.packageName) != p.hidden ->
                CommandResults.failed(command, "Application visibility did not change")
            else -> CommandResults.done(command)
        }
    }.getOrElse { CommandResults.failed(command, it.message ?: "Invalid app visibility request") }
}
