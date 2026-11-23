package com.mdmesh.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/** The device id and check-in secret, once both exist. */
data class Credentials(val deviceId: String, val secret: String) {
    companion object {
        /**
         * The saved credentials after each successful check-in ([checkIns]), when both exist. A check-in is what
         * changes them: the one that enrolls a device saves them, and one that recovers a refused enrollment
         * ([EnrollmentManager]) saves the secret the server holds. Not deduplicated: the collector compares each
         * one with what it holds ([CredentialsFollower]), so a stale value it was handed elsewhere is corrected at
         * the next success.
         */
        fun changes(checkIns: Flow<Unit>, read: suspend () -> Pair<String?, String?>): Flow<Credentials> =
            checkIns.mapNotNull {
                val (id, secret) = read()
                if (id.isNullOrBlank() || secret.isNullOrBlank()) null else Credentials(id, secret)
            }
    }
}
