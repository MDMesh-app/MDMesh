package com.mdmesh.core.sync

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription

/** The device id and check-in secret, once both exist. */
data class Credentials(val deviceId: String, val secret: String) {
    companion object {
        /**
         * Suspends until [read] returns both, reading now and again after each successful check-in ([checkIns]):
         * the check-in that enrolls a device is the one that saves them. It subscribes before the first read, so a
         * check-in that completes in between is not missed.
         */
        suspend fun await(checkIns: SharedFlow<Unit>, read: suspend () -> Pair<String?, String?>): Credentials =
            checkIns
                .onSubscription { emit(Unit) }
                .map { read() }
                .first { (id, secret) -> !id.isNullOrBlank() && !secret.isNullOrBlank() }
                .let { (id, secret) -> Credentials(id!!, secret!!) }
    }
}
