package com.mdmesh.remote

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

    enum class Mode { VIEW, CONTROL }

    /** Begin a session. [sessionId] is server-issued and echoed in results. */
    suspend fun start(sessionId: String, mode: Mode): Result<Unit>

    /** Tear down the session and release capture/injection resources. */
    suspend fun stop(sessionId: String): Result<Unit>

    /** True while a session is active. */
    fun isActive(): Boolean
}
