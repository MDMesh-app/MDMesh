package com.mdmesh.proto

import kotlinx.serialization.Serializable

/**
 * Request/response bodies for the `/rest/public/agent/v1` endpoints.
 * Mirrors `proto/agent-*.schema.json` and `proto/endpoints.md`.
 *
 * These reuse the capability-matrix building blocks ([AgentInfo], [DeviceInfo],
 * [Capabilities], [CommandEnvelope], [CommandResult]) so there is a single source of
 * truth for the wire shapes.
 */

/** POST /enroll body. Token-gated; the server issues the device id (so [device].id is ignored here). */
@Serializable
data class AgentEnrollRequest(
    val protocolVersion: String = ProtocolJson.PROTOCOL_VERSION,
    val enrollToken: String,
    val agent: AgentInfo,
    val device: DeviceInfo,
    val capabilities: Capabilities,
    /** Stable, permission-free device id (enrollment-specific id / ANDROID_ID) so the server
     *  can recognise a re-enrolling physical device and flag duplicate rows. */
    val hardwareId: String? = null,
)

/**
 * `data` payload of the enroll response: the server-issued opaque device id and the
 * per-device secret. The secret is presented as `Authorization: Bearer <secret>` on every
 * check-in; it is returned exactly once, here, and must be persisted immediately.
 */
@Serializable
data class AgentEnrollResponse(
    val protocolVersion: String = ProtocolJson.PROTOCOL_VERSION,
    val deviceId: String,
    val configurationName: String? = null,
    val deviceSecret: String? = null,
)

/** POST /checkin body: re-advertise capabilities, ack prior commands, pull the next batch. */
@Serializable
data class AgentCheckInRequest(
    val protocolVersion: String = ProtocolJson.PROTOCOL_VERSION,
    val deviceId: String,
    val capabilities: Capabilities,
    /** Acks for commands delivered in a previous cycle. */
    val results: List<CommandResult> = emptyList(),
    /** Compact device-state snapshot piggybacked on each check-in (powers the admin console). */
    val state: AgentDeviceStateDto? = null,
    /** Full device census (supersedes [state], which stays for one version for back-compat). */
    val telemetry: TelemetrySnapshot? = null,
    /** Buffered lifecycle events flushed on this check-in. */
    val events: List<TelemetryEventDto> = emptyList(),
    /** Same stable device id as enroll — lets already-enrolled devices backfill it. */
    val hardwareId: String? = null,
)

/** Compact device-state snapshot (device -> server) reported on each check-in. */
@Serializable
data class AgentDeviceStateDto(
    val battery: Int,
    val charging: Boolean,
    val locked: Boolean,
    val kioskActive: Boolean,
    val androidRelease: String,
    val lastBootAt: Long,
    /** Installed agent versionName, e.g. "0.1.4". Null on older agents that don't report it. */
    val agentVersion: String? = null,
    /** Current connectivity power mode ("adaptive" | "alwaysOn"). */
    val powerMode: String? = null,
)

/** `data` payload of the checkin response: capability-gated commands to reconcile. */
@Serializable
data class AgentCheckInResponse(
    val protocolVersion: String = ProtocolJson.PROTOCOL_VERSION,
    val commands: List<CommandEnvelope> = emptyList(),
)
