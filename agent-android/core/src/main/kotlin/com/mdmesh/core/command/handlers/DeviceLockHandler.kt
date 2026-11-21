package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult

/**
 * `device.lock` — immediately lock the screen ([android.app.admin.DevicePolicyManager.lockNow]).
 *
 * Without a secure screen lock (PIN, pattern, password) that only turns the screen off, so the
 * result still says `done` but its detail says the device isn't protected. [isDeviceSecure] is
 * [android.app.KeyguardManager.isDeviceSecure]; if it can't be read, the plain `done` stands.
 */
class DeviceLockHandler(
    private val lockNow: () -> Unit,
    private val isDeviceSecure: () -> Boolean,
) : CommandHandler {

    override val type: String = "device.lock"

    override suspend fun handle(command: CommandEnvelope): CommandResult =
        runCatching {
            lockNow()
            if (runCatching(isDeviceSecure).getOrDefault(true)) {
                CommandResults.done(command)
            } else {
                CommandResults.done(command, NOT_SECURE)
            }
        }.getOrElse { CommandResults.failed(command, it.message ?: "lock failed") }

    internal companion object {
        const val NOT_SECURE = "No screen lock is set: the screen turned off, but the device isn't protected."
    }
}
