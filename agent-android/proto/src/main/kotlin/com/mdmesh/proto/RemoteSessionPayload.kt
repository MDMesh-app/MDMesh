package com.mdmesh.proto

import kotlinx.serialization.Serializable

/** Additive payloads for the view-only remote-session command pair (protocol 1.10). */
@Serializable
data class RemoteStartSessionPayload(
    /** Server-issued opaque UUID; never reuse it for another device or session. */
    val sessionId: String,
    val mode: String = RemoteSessionMode.VIEW,
    /** ISO-8601 deadline after which the agent must refuse the request. */
    val expiresAt: String,
    /** Optional earlier deadline for the one-time local capture-consent prompt. */
    val consentExpiresAt: String? = null,
    /** Agent-only signaling join information. Never include it in telemetry/events. */
    val signaling: RemoteSignalingPayload,
)

@Serializable
data class RemoteSignalingPayload(
    val endpoint: String,
    val credential: String,
)

@Serializable
data class RemoteStopSessionPayload(
    val sessionId: String,
    val reason: String = "stopped",
)

object RemoteSessionMode {
    const val VIEW = "view"
    const val CONTROL = "control"
}
