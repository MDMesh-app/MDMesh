package com.mdmesh.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class RemoteViewFeasibilityState(
    val message: String,
    val active: Boolean,
)

/** Release builds intentionally contain no Step-0 screen-capture probe. */
object RemoteViewFeasibilityProbe {
    val state: StateFlow<RemoteViewFeasibilityState> =
        MutableStateFlow(RemoteViewFeasibilityState("", active = false))

    @Suppress("FunctionOnlyReturningConstant")
    fun isAvailable(): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun requestConsent(activity: Activity, launcher: ActivityResultLauncher<Intent>) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onConsentResult(context: Context, resultCode: Int, resultData: Intent?) = Unit
}
