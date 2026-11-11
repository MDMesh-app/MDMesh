package com.mdmesh.agent

import com.mdmesh.core.sync.EnrollmentFailure
import com.mdmesh.core.sync.SyncStatus

/** The status screen's DEVICE ID line, from the persisted state (pure, so it is tested as the screen shows it). */
object DeviceIdLabel {

    /**
     * [deviceId] (null before enrollment), [credentialsLost] (persisted: the server refused this device's credentials
     * and had nothing to resume), [lastError] (the last failed sync, replaced by every later one), [time] formats a
     * failure's time.
     */
    fun of(
        deviceId: String?,
        credentialsLost: Boolean,
        lastError: SyncStatus.Failure?,
        time: (Long) -> String,
    ): String =
        when {
            deviceId.isNullOrBlank() ->
                lastError?.let { "Enrolling… (last error: ${it.message} at ${time(it.atMillis)})" } ?: "Enrolling…"
            credentialsLost -> "$deviceId\n${EnrollmentFailure.CREDENTIALS_LOST}"
            else -> deviceId
        }
}
