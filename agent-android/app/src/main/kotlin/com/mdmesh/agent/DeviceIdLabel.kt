package com.mdmesh.agent

import com.mdmesh.core.sync.CredentialsLost
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
     * [credentialsLostMark] is the stored lost-credentials mark (null: none); any check-in that succeeded since the
     * mark ([lastSuccessAtMs] changed) shows they work again, so the warning goes at once. The rule is
     * [CredentialsLost.stillLost], the one the agent uses to clear the mark: no clock comparison.
     */
    fun of(
        deviceId: String?,
        credentialsLostMark: Long?,
        lastSuccessAtMs: Long?,
        lastError: SyncStatus.Failure?,
    ): Status = if (deviceId.isNullOrBlank()) {
        Status.Enrolling(lastError)
    } else {
        Status.Enrolled(deviceId, CredentialsLost.stillLost(credentialsLostMark, lastSuccessAtMs))
    }
}
