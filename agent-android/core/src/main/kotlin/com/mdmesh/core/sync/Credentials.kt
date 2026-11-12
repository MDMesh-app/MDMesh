package com.mdmesh.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull

/** The device id and check-in secret, once both exist. */
data class Credentials(val deviceId: String, val secret: String) {
    companion object {
        /**
         * The saved credentials after each successful check-in ([checkIns]), when both exist, each time they differ
         * from the last ones emitted. A check-in is what changes them: the one that enrolls a device saves them, and
         * one that recovers a refused enrollment ([EnrollmentManager]) saves the secret the server holds. Collected
         * for the wake channel's lifetime, subscribed before anything is read, so no such check-in is missed.
         */
        fun changes(checkIns: Flow<Unit>, read: suspend () -> Pair<String?, String?>): Flow<Credentials> =
            checkIns
                .mapNotNull {
                    val (id, secret) = read()
                    if (id.isNullOrBlank() || secret.isNullOrBlank()) null else Credentials(id, secret)
                }
                .distinctUntilChanged()
    }
}
