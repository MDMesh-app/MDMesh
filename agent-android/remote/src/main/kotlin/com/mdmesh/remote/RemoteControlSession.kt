package com.mdmesh.remote

import com.mdmesh.proto.RemoteStartSessionPayload

/**
 * A live remote-control session between an administrator and this device.
 *
 * Two modes (see `proto/README.md` § capability tiers):
 *  - [Mode.VIEW]    — MediaProjection screen capture only (Tier 0, universal).
 *  - [Mode.CONTROL] — capture + AccessibilityService input injection (Tier 1).
 *
 * The implementation is deliberately view-only. It never exposes an input data
 * channel or enables the accessibility service.
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

/** A foreground activity consumes this request to present Android's capture-consent UI. */
data class RemoteViewConsentRequest(val sessionId: String)
data class RemoteViewAgentCredentials(val deviceId: String, val secret: String)
data class RemoteViewStatus(val message: String, val active: Boolean)

/** UI-facing part of a view session; separated so command handling never needs an Activity. */
interface RemoteViewConsentController {
    val pendingConsent: kotlinx.coroutines.flow.StateFlow<RemoteViewConsentRequest?>
    /** Short, safe operational status for the kiosk; no credentials, URLs, or signaling payloads. */
    val status: kotlinx.coroutines.flow.StateFlow<RemoteViewStatus>
    fun claimConsentRequest(): RemoteViewConsentRequest?
    fun captureConsentIntent(): android.content.Intent
    fun consentResult(sessionId: String, resultCode: Int, data: android.content.Intent?)
    fun consentDenied(sessionId: String)
}
