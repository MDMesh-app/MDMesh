package com.mdmesh.proto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Server -> device. A single command.
 *
 * The server MUST NOT send a command whose [requiresCapability] is absent from the
 * device's latest [CapabilityMatrix]. The device, in turn, MUST tolerate unknown
 * [type] values and reply with [CommandStatus.UNSUPPORTED] rather than crashing.
 *
 * [payload] is intentionally a raw [JsonObject]: each command type owns its own
 * payload shape (see `proto/registry.md`), decoded lazily by the handler for that
 * type. Keeping it untyped here is what lets the registry grow without touching
 * this class.
 *
 * Mirrors `proto/command-envelope.schema.json`.
 */
@Serializable
data class CommandEnvelope(
    val protocolVersion: String = ProtocolJson.PROTOCOL_VERSION,
    /** UUID; idempotency key. The device must dedupe by this. */
    val commandId: String,
    /** ISO-8601 date-time. */
    val issuedAt: String,
    /** Drop if older than this when received. 0 / null = no expiry. */
    val ttlSeconds: Int? = null,
    /** Open registry value, e.g. `policy.apply`, `app.install`, `remote.startSession`. */
    val type: String,
    /** Capability key the device must advertise for this command to be valid. */
    val requiresCapability: String? = null,
    val payload: JsonObject? = null,
)
