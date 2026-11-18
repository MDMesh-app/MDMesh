package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.install.ApkPart
import com.mdmesh.core.install.InstallManager
import com.mdmesh.core.install.InstallOutcome
import com.mdmesh.core.install.InstallRequest
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `app.install` — silently install (or upgrade) an app as Device Owner. Payload:
 * `{ url, packageName, versionCode?, sha256?, runAfterInstall?, parts? }`. A `parts` list (`[{url, sha256?}]`)
 * installs a split-APK bundle (base + splits) in one session; without it the single `url`/`localPath` is used.
 * A hex `sha256` that is present is always verified; it is required only while the server says it sends one with
 * every install ([com.mdmesh.core.install.InstallIntegrityPolicy]). Version gating, downgrade-blocking and the
 * install itself are delegated to [InstallManager].
 *
 * After a successful install or update the order is: [afterInstall], then (with `runAfterInstall`) [launch], then the
 * result. So an app that reads its managed configuration or permissions at startup finds them in place.
 *
 * @param install installs the request ([InstallManager.install]); it never starts the app.
 * @param launch starts the app's main activity ([InstallManager.launch]); this handler is the one place that does.
 * @param afterInstall applies the configuration's permission rules and managed configuration for the package
 *   (ConfigApplier.applyAfterInstall). Its failure never fails the install nor stops the launch.
 */
class AppInstallHandler(
    private val install: suspend (InstallRequest) -> InstallOutcome,
    private val launch: (packageName: String) -> Unit,
    private val afterInstall: suspend (packageName: String) -> Unit = {},
) : CommandHandler {

    constructor(installManager: InstallManager, afterInstall: suspend (packageName: String) -> Unit = {}) :
        this(installManager::install, installManager::launch, afterInstall)

    override val type: String = "app.install"

    @Serializable
    private data class PartPayload(
        val url: String? = null,
        val localPath: String? = null,
        val sha256: String? = null,
    )

    @Serializable
    private data class Payload(
        val url: String? = null,
        val localPath: String? = null,
        val packageName: String,
        val versionCode: Long? = null,
        val sha256: String? = null,
        val runAfterInstall: Boolean = false,
        val parts: List<PartPayload> = emptyList(),
    )

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = command.payload
            ?: return CommandResults.failed(command, "app.install requires a payload")
        val p = runCatching {
            ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), payload)
        }.getOrElse { return CommandResults.failed(command, "bad payload: ${it.message}") }

        val outcome = install(
            InstallRequest(
                url = p.url,
                localPath = p.localPath,
                packageName = p.packageName,
                versionCode = p.versionCode,
                sha256 = p.sha256,
                parts = p.parts.map { ApkPart(url = it.url, localPath = it.localPath, sha256 = it.sha256) },
            ),
        )
        return when (outcome) {
            InstallOutcome.Success -> {
                // The install succeeded whatever the follow-up does; the server's re-send applies the rules later.
                runCatching { afterInstall(p.packageName) }
                    .onFailure { if (it is CancellationException) throw it }
                if (p.runAfterInstall) launch(p.packageName)
                CommandResults.done(command)
            }
            is InstallOutcome.Skipped -> CommandResults.done(command, "skipped: ${outcome.reason}")
            is InstallOutcome.Failure -> CommandResults.failed(command, outcome.reason)
        }
    }
}
