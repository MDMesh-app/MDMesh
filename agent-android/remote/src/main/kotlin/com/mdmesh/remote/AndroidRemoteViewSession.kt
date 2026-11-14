@file:Suppress("MaxLineLength", "TooManyFunctions")

package com.mdmesh.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.mdmesh.proto.RemoteStartSessionPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * View-only session coordinator. Command handling records a pending consent request;
 * a visible activity alone can claim it and launch Android's system-owned consent UI.
 */
class AndroidRemoteViewSession(
    private val context: Context,
    private val report: (String, String) -> Unit,
    private val credentials: suspend () -> RemoteViewAgentCredentials?,
) : RemoteControlSession, RemoteViewConsentController {
    private val main = Handler(Looper.getMainLooper())
    private val mutablePending = MutableStateFlow<RemoteViewConsentRequest?>(null)
    override val pendingConsent: StateFlow<RemoteViewConsentRequest?> = mutablePending

    @Volatile private var request: RemoteStartSessionPayload? = null
    @Volatile private var capture: RemoteViewCapture? = null

    override suspend fun start(request: RemoteStartSessionPayload): Result<Unit> = runCatching {
        require(!isActive()) { "a remote-view session is already active" }
        require(Instant.parse(request.expiresAt).isAfter(Instant.now())) { "remote-view session expired" }
        this.request = request
        mutablePending.value = RemoteViewConsentRequest(request.sessionId)
        report("remoteViewConsentRequested", "session=${request.sessionId}")
    }

    override fun claimConsentRequest(): RemoteViewConsentRequest? {
        val pending = mutablePending.value ?: return null
        mutablePending.value = null
        return pending
    }

    /** Called by a visible activity after Android returns the MediaProjection result. */
    override fun consentResult(sessionId: String, resultCode: Int, resultData: Intent?) {
        val active = request
        if (active == null || active.sessionId != sessionId) return
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            consentDenied(sessionId)
            return
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, RemoteViewCaptureService::class.java)
                .putExtra(RemoteViewCaptureService.EXTRA_SESSION_ID, sessionId)
                .putExtra(RemoteViewCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(RemoteViewCaptureService.EXTRA_RESULT_DATA, resultData),
        )
    }

    override fun consentDenied(sessionId: String) {
        if (request?.sessionId != sessionId) return
        report("remoteViewDenied", "session=$sessionId")
        clear("denied")
    }

    /** Called by the foreground service after it has entered foreground state. */
    suspend fun startCapture(sessionId: String, resultData: Intent): Result<Unit> = runCatching {
        val active = requireNotNull(request) { "remote-view session is not active" }
        require(active.sessionId == sessionId) { "remote-view session mismatch" }
        require(Instant.parse(active.expiresAt).isAfter(Instant.now())) { "remote-view session expired" }
        check(capture == null) { "remote-view capture is already active" }
        val device = requireNotNull(credentials()) { "device credentials are unavailable" }
        RemoteViewCapture(context, active, device, ::ended).also {
            capture = it
            it.start(resultData)
        }
        report("remoteViewSignaling", "session=$sessionId")
    }

    override suspend fun stop(sessionId: String, reason: String): Result<Unit> = runCatching {
        if (request?.sessionId == sessionId) clear(reason)
    }

    override suspend fun stopForConfigurationChange(): Result<Unit> = runCatching { clear("configuration changed") }
    override fun isActive(): Boolean = request != null

    private fun ended(reason: String) {
        report("remoteViewEnded", "session=${request?.sessionId ?: "unknown"}; reason=$reason")
        clear(reason)
    }

    private fun clear(reason: String) {
        mutablePending.value = null
        val current = capture
        capture = null
        request = null
        current?.stop(reason)
        main.removeCallbacksAndMessages(null)
        Log.i(TAG, "remote view stopped: $reason")
    }

    override fun captureConsentIntent(): Intent = context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
    fun serviceDestroyed() { if (request != null) clear("capture service stopped") }
    private companion object { const val TAG = "RemoteViewSession" }
}
