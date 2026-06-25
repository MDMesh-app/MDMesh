package com.mdmesh.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Device -> server. Ack / outcome for a command.
 *
 * [CommandStatus.UNSUPPORTED] is how an older agent tells a newer server it does
 * not recognise a command type — the cornerstone of graceful degradation.
 *
 * Mirrors `proto/command-result.schema.json`.
 */
@Serializable
data class CommandResult(
    val protocolVersion: String = ProtocolJson.PROTOCOL_VERSION,
    val commandId: String,
    val status: CommandStatus,
    /** Human-readable diagnostic; required when [status] is [CommandStatus.FAILED]. */
    val detail: String? = null,
    /** ISO-8601 date-time. */
    val completedAt: String,
)

@Serializable
enum class CommandStatus {
    @SerialName("accepted") ACCEPTED,
    @SerialName("done") DONE,
    @SerialName("failed") FAILED,
    @SerialName("unsupported") UNSUPPORTED,
    @SerialName("expired") EXPIRED,
}
