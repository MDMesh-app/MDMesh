package com.mdmesh.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RemoteViewFeasibilityState(
    val message: String,
    val active: Boolean,
)

/**
 * Debug-only manual probe for Step 0 of the LAN remote-view POC.
 *
 * This is deliberately local-only: it is not command-driven, creates no server
 * session, sends no media, and is absent from release builds. It answers only
 * whether the S8 can present and accept Android's capture consent while kiosk
 * is active and whether a foreground service can own a short projection.
 */
object RemoteViewFeasibilityProbe {

    private val mutableState = MutableStateFlow(RemoteViewFeasibilityState("", active = false))
    val state: StateFlow<RemoteViewFeasibilityState> = mutableState.asStateFlow()

    @Suppress("FunctionOnlyReturningConstant")
    fun isAvailable(): Boolean = true

    fun requestConsent(
        activity: Activity,
        launcher: ActivityResultLauncher<Intent>,
    ) {
        mutableState.value = RemoteViewFeasibilityState("Waiting for screen-share approval…", active = false)
        val manager = activity.getSystemService(MediaProjectionManager::class.java)
        launcher.launch(manager.createScreenCaptureIntent())
    }

    fun onConsentResult(context: Context, resultCode: Int, resultData: Intent?) {
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            mutableState.value = RemoteViewFeasibilityState("Screen-share test was not approved.", active = false)
            Toast.makeText(context, "Screen-share probe was not approved.", Toast.LENGTH_LONG).show()
            return
        }
        mutableState.value = RemoteViewFeasibilityState("Starting screen-share test…", active = false)
        val intent = Intent(context, RemoteViewFeasibilityService::class.java)
            .putExtra(RemoteViewFeasibilityService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(RemoteViewFeasibilityService.EXTRA_RESULT_DATA, resultData)
        ContextCompat.startForegroundService(context, intent)
        Toast.makeText(context, "Screen-share probe active for 30 seconds.", Toast.LENGTH_LONG).show()
    }

    internal fun markActive() {
        mutableState.value = RemoteViewFeasibilityState(
            "Screen-share test is active for 30 seconds. No video is sent.",
            active = true,
        )
    }

    internal fun markStopped() {
        mutableState.value = RemoteViewFeasibilityState("Screen-share test ended.", active = false)
    }

    internal fun markFailed() {
        mutableState.value = RemoteViewFeasibilityState("Screen-share test could not start.", active = false)
    }
}
