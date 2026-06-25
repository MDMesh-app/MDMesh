package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult

/** `device.lock` — immediately lock the screen ([android.app.admin.DevicePolicyManager.lockNow]). */
class DeviceLockHandler(
    private val handle: DpmHandle,
) : CommandHandler {

    override val type: String = "device.lock"

    override suspend fun handle(command: CommandEnvelope): CommandResult =
        runCatching {
            handle.dpm.lockNow()
            CommandResults.done(command)
        }.getOrElse { CommandResults.failed(command, it.message ?: "lock failed") }
}
