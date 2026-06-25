package com.mdmesh.core.command.handlers

import android.os.Build
import com.mdmesh.core.action.ResetPasswordTokenStore
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable

/** `device.passcodeReset` — set or clear the device passcode via the DO reset token (API 26+). */
class DevicePasscodeResetHandler(
    private val handle: DpmHandle,
    private val tokenStore: ResetPasswordTokenStore,
) : CommandHandler {

    override val type: String = DeviceAction.PASSCODE_RESET

    @Serializable
    private data class Payload(val newPassword: String? = null)

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return CommandResults.unsupported(command, "passcode reset needs API 26+")
        }
        val token = tokenStore.token()
            ?: return CommandResults.failed(command, "no reset-password token provisioned")
        val pwd = command.payload
            ?.let { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }
            ?.newPassword ?: ""
        val ok = handle.dpm.resetPasswordWithToken(handle.admin, pwd, token, 0)
        if (ok) CommandResults.done(command) else CommandResults.failed(command, "resetPasswordWithToken rejected")
    }.getOrElse { CommandResults.failed(command, it.message ?: "passcode reset failed") }
}
