package com.mdmesh.agent

import com.mdmesh.core.sync.SyncStatus

/**
 * What the status screen's DEVICE ID area shows, decided from the persisted state (pure, so it is tested with the state
 * production shows it in). The words are string resources; MainActivity formats them.
 */
object DeviceIdLabel {

    sealed interface Status {
        /** Not enrolled yet; [lastError] is the last failed sync, if any. */
        data class Enrolling(val lastError: SyncStatus.Failure?) : Status

        /** Enrolled as [deviceId]; [credentialsLost]: the server refused its credentials and could restore nothing. */
        data class Enrolled(val deviceId: String, val credentialsLost: Boolean) : Status
    }

    /**
     * [credentialsLostAt] is when the credentials were marked lost (null: they weren't); a check-in that succeeded at
     * or after it ([lastSuccessAtMs]) shows they work again, so the warning goes at once.
     */
    fun of(
        deviceId: String?,
        credentialsLostAt: Long?,
        lastSuccessAtMs: Long?,
        lastError: SyncStatus.Failure?,
    ): Status = if (deviceId.isNullOrBlank()) {
        Status.Enrolling(lastError)
    } else {
        val lost = credentialsLostAt != null && (lastSuccessAtMs == null || lastSuccessAtMs < credentialsLostAt)
        Status.Enrolled(deviceId, lost)
    }
}
