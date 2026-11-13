package com.mdmesh.remote

import com.mdmesh.proto.RemoteStartSessionPayload

/**
 * A live remote-control session between an administrator and this device.
 *
 * Two modes (see `proto/README.md` § capability tiers):
 *  - [Mode.VIEW]    — MediaProjection screen capture only (Tier 0, universal).
 *  - [Mode.CONTROL] — capture + AccessibilityService input injection (Tier 1).
 *
 * Capture (MediaProjection) and signaling/transport (WebRTC) are deferred — see
 * this module's README. The contract is defined now so `:core` can dispatch
 * `remote.startSession` / `remote.stopSession` commands against a stable surface.
 */
interface RemoteControlSession {

    /** Begin a view session after validating its server-issued opaque id and expiry. */
    suspend fun start(request: RemoteStartSessionPayload): Result<Unit>

    /** Tear down the session and release capture/injection resources. */
    suspend fun stop(sessionId: String, reason: String = "stopped"): Result<Unit>

    /** A new desired configuration is a management boundary and ends any active view. */
    suspend fun stopForConfigurationChange(): Result<Unit>

    /** True while a session is active. */
    fun isActive(): Boolean
}
