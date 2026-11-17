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
    private val scheduleCheckIn: () -> Unit,
    private val credentials: suspend () -> RemoteViewAgentCredentials?,
) : RemoteControlSession, RemoteViewConsentController {
    private val main = Handler(Looper.getMainLooper())
    private val mutablePending = MutableStateFlow<RemoteViewConsentRequest?>(null)
    override val pendingConsent: StateFlow<RemoteViewConsentRequest?> = mutablePending
    private val mutableStatus = MutableStateFlow(RemoteViewStatus("", active = false))
    override val status: StateFlow<RemoteViewStatus> = mutableStatus

    @Volatile private var request: RemoteStartSessionPayload? = null
    @Volatile private var capture: RemoteViewCapture? = null
    @Volatile private var consentDeadline: Runnable? = null

    override suspend fun start(request: RemoteStartSessionPayload): Result<Unit> = runCatching {
        require(!isActive()) { "a remote-view session is already active" }
        require(Instant.parse(request.expiresAt).isAfter(Instant.now())) { "remote-view session expired" }
        this.request = request
        mutablePending.value = RemoteViewConsentRequest(request.sessionId)
        mutableStatus.value = RemoteViewStatus("Waiting for administrator screen-share approval…", active = false)
        scheduleConsentDeadline(request)
        reportEvent("remoteViewConsentRequested", "session=${request.sessionId}")
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
        mutableStatus.value = RemoteViewStatus("Starting remote screen share…", active = false)
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
        mutableStatus.value = RemoteViewStatus("Remote screen-share request was not approved.", active = false)
        reportEvent("remoteViewDenied", "session=$sessionId")
        clear("denied")
    }

    /** Called by the foreground service after it has entered foreground state. */
    suspend fun startCapture(sessionId: String, resultData: Intent): Result<Unit> {
        var startupStage = "creating capture"
        val result = runCatching {
            val active = requireNotNull(request) { "remote-view session is not active" }
            require(active.sessionId == sessionId) { "remote-view session mismatch" }
            require(Instant.parse(active.expiresAt).isAfter(Instant.now())) { "remote-view session expired" }
            check(capture == null) { "remote-view capture is already active" }
            val device = requireNotNull(credentials()) { "device credentials are unavailable" }
            val ownedCapture = arrayOfNulls<RemoteViewCapture>(1)
            val newCapture = RemoteViewCapture(
                context,
                active,
                device,
                { reason -> ended(sessionId, ownedCapture[0], reason) },
                {
                    mutableStatus.value = RemoteViewStatus("Remote screen share is active.", active = true)
                    reportEvent("remoteViewSignaling", "session=$sessionId; signaling socket opened")
                },
                { detail ->
                    // Visible only during an active POC session; do not persist
                    // signaling metadata or add normal device event traffic.
                    mutableStatus.value = RemoteViewStatus(detail, active = true)
                },
            )
            // Bind the asynchronous end callback to this capture only. An old teardown must
            // never clear a request accepted after it was stopped.
            ownedCapture[0] = newCapture
            capture = newCapture
            startupStage = newCapture.currentStartupStage()
            newCapture.start(resultData)
            mutableStatus.value = RemoteViewStatus("Screen capture started; connecting to viewer…", active = true)
            Unit
        }
        result.exceptionOrNull()?.let { failure ->
            val current = capture
            val detail = "capture startup failed at ${current?.currentStartupStage() ?: startupStage}: ${failure.javaClass.simpleName}"
            mutableStatus.value = RemoteViewStatus("Remote view failed: $detail", active = false)
            if (current != null) {
                current.stop(detail)
            } else {
                reportEvent("remoteViewEnded", "session=$sessionId; reason=$detail")
                clear("capture startup failed", sessionId)
            }
        }
        return result
    }

    override suspend fun stop(sessionId: String, reason: String): Result<Unit> = runCatching {
        if (request?.sessionId == sessionId) clear(reason, sessionId)
    }

    override suspend fun stopForConfigurationChange(): Result<Unit> = runCatching { clear("configuration changed") }
    override fun isActive(): Boolean = request != null

    private fun ended(sessionId: String, finished: RemoteViewCapture?, reason: String) {
        if (request?.sessionId != sessionId || capture !== finished) return
        mutableStatus.value = RemoteViewStatus("Remote view ended: $reason", active = false)
        reportEvent("remoteViewEnded", "session=$sessionId; reason=$reason")
        clear(reason, sessionId)
    }

    /** Persist a short lifecycle fact, then ask the normal authenticated check-in path to flush it promptly. */
    private fun reportEvent(type: String, detail: String) {
        report(type, detail)
        scheduleCheckIn()
    }

    private fun clear(reason: String, expectedSessionId: String? = null) {
        if (expectedSessionId != null && request?.sessionId != expectedSessionId) return
        mutablePending.value = null
        val current = capture
        val wasActive = request != null
        capture = null
        request = null
        consentDeadline?.let(main::removeCallbacks)
        consentDeadline = null
        current?.stop(reason)
        if (wasActive) context.stopService(Intent(context, RemoteViewCaptureService::class.java))
        Log.i(TAG, "remote view stopped: $reason")
    }

    private fun scheduleConsentDeadline(active: RemoteStartSessionPayload) {
        consentDeadline?.let(main::removeCallbacks)
        val deadline = active.consentExpiresAt?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
            ?: runCatching { Instant.parse(active.expiresAt) }.getOrNull()
            ?: return
        val delay = (deadline.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L)
        val task = Runnable {
            if (request?.sessionId == active.sessionId && capture == null) {
                mutableStatus.value = RemoteViewStatus("Remote screen-share consent timed out.", active = false)
                reportEvent("remoteViewEnded", "session=${active.sessionId}; reason=consent timed out")
                clear("consent timed out", active.sessionId)
            }
        }
        consentDeadline = task
        main.postDelayed(task, delay)
    }

    override fun captureConsentIntent(): Intent = context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
    fun serviceDestroyed() { if (request != null && capture != null) clear("capture service stopped") }
    private companion object { const val TAG = "RemoteViewSession" }
}
