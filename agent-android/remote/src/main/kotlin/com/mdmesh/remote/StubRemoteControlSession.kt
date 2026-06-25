package com.mdmesh.remote

/**
 * Placeholder [RemoteControlSession]. Tracks session state but performs no capture
 * or signaling yet. Replace once MediaProjection + WebRTC land.
 */
class StubRemoteControlSession : RemoteControlSession {

    @Volatile private var activeSessionId: String? = null

    override suspend fun start(sessionId: String, mode: RemoteControlSession.Mode): Result<Unit> {
        activeSessionId = sessionId
        return Result.success(Unit)
    }

    override suspend fun stop(sessionId: String): Result<Unit> {
        if (activeSessionId == sessionId) activeSessionId = null
        return Result.success(Unit)
    }

    override fun isActive(): Boolean = activeSessionId != null
}
