package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.install.InstallManager
import com.mdmesh.core.install.InstallOutcome
import com.mdmesh.core.install.InstallRequest
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * `app.install` — silently install (or upgrade) an APK as Device Owner. Payload:
 * `{ url, packageName, versionCode?, sha256?, runAfterInstall? }`. Version gating,
 * downgrade-blocking, and the install itself are delegated to [InstallManager].
 */
class AppInstallHandler(
    private val installManager: InstallManager,
) : CommandHandler {

    override val type: String = "app.install"

    @Serializable
    private data class Payload(
        val url: String? = null,
        val localPath: String? = null,
        val packageName: String,
        val versionCode: Long? = null,
        val sha256: String? = null,
        val runAfterInstall: Boolean = false,
    )

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = command.payload
            ?: return CommandResults.failed(command, "app.install requires a payload")
        val p = runCatching {
            ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), payload)
        }.getOrElse { return CommandResults.failed(command, "bad payload: ${it.message}") }

        val outcome = installManager.install(
            InstallRequest(
                url = p.url,
                localPath = p.localPath,
                packageName = p.packageName,
                versionCode = p.versionCode,
                sha256 = p.sha256,
                runAfterInstall = p.runAfterInstall,
            ),
        )
        return when (outcome) {
            InstallOutcome.Success -> CommandResults.done(command)
            is InstallOutcome.Skipped -> CommandResults.done(command, "skipped: ${outcome.reason}")
            is InstallOutcome.Failure -> CommandResults.failed(command, outcome.reason)
        }
    }
}
