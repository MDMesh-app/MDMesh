package com.mdmesh.core.command

import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.CommandStatus
import java.time.Instant

/**
 * Small builders for the common [CommandResult] shapes, so handlers stay terse and
 * always stamp `completedAt`. Centralising these keeps the protocol invariants
 * (e.g. `detail` required on failure) in one place.
 */
object CommandResults {

    fun done(command: CommandEnvelope, detail: String? = null): CommandResult =
        result(command, CommandStatus.DONE, detail)

    fun accepted(command: CommandEnvelope, detail: String? = null): CommandResult =
        result(command, CommandStatus.ACCEPTED, detail)

    fun failed(command: CommandEnvelope, detail: String): CommandResult =
        result(command, CommandStatus.FAILED, detail)

    fun unsupported(command: CommandEnvelope, detail: String? = null): CommandResult =
        result(command, CommandStatus.UNSUPPORTED, detail)

    fun expired(command: CommandEnvelope, detail: String? = null): CommandResult =
        result(command, CommandStatus.EXPIRED, detail)

    private fun result(
        command: CommandEnvelope,
        status: CommandStatus,
        detail: String?,
    ): CommandResult = CommandResult(
        commandId = command.commandId,
        status = status,
        detail = detail,
        completedAt = Instant.now().toString(),
    )
}
