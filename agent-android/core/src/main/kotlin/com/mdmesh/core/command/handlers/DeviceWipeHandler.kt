package com.mdmesh.core.command.handlers

import android.os.Build
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction

/**
 * `device.wipe` — factory-reset the whole device. Irreversible. Gated by the `device.wipe`
 * capability token AND UI type-to-confirm. Reports `done` best-effort, though the process is
 * usually torn down before the ack is sent.
 *
 * On API 34+ a Device Owner must use [android.app.admin.DevicePolicyManager.wipeDevice] for a
 * full factory reset; the legacy [android.app.admin.DevicePolicyManager.wipeData] is treated as
 * "remove the calling user", which fails on the system user ("User 0 ... cannot be removed").
 */
class DeviceWipeHandler(
    private val handle: DpmHandle,
) : CommandHandler {

    override val type: String = DeviceAction.WIPE

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            handle.dpm.wipeDevice(0)
        } else {
            @Suppress("DEPRECATION")
            handle.dpm.wipeData(0)
        }
        CommandResults.done(command)
    }.getOrElse { CommandResults.failed(command, it.message ?: "wipe failed") }
}
