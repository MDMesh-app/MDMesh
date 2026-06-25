package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult

/**
 * `config.sync` — triggers a full reconcile. No payload. Since the sync loop *is*
 * the reconcile, simply acknowledging is correct; the next check-in carries the
 * fresh matrix.
 */
class ConfigSyncHandler : CommandHandler {

    override val type: String = "config.sync"

    override suspend fun handle(command: CommandEnvelope): CommandResult =
        CommandResults.done(command, "reconcile acknowledged")
}
