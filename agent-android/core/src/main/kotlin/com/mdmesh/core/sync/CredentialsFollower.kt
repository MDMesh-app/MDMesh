package com.mdmesh.core.sync

import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.transport.TransportManager
import com.mdmesh.core.transport.WakeSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The credentials the wake socket dials with, and what keeps them current: the one place that hands them to
 * [TransportManager]. The host (the foreground service) decides only whether the socket is wanted now ([hot]).
 *
 * A check-in that saves new credentials (the one that enrolls a device, or one that recovers the secret the server
 * holds after a refused enrollment) moves the socket onto them at once, instead of it dialling with none or a refused
 * one until the service restarts.
 */
class CredentialsFollower(
    private val scope: CoroutineScope,
    private val successes: Flow<Unit>,
    private val identity: DeviceIdentity,
    private val transport: TransportManager,
    /** Whether the socket should be held now (the host's power rules). */
    private val hot: () -> Boolean,
    private val onWake: suspend (WakeSignal) -> Unit,
) {
    /** What the socket dials with; null until both the device id and the secret are saved. */
    @Volatile var current: Credentials? = null
        private set

    init {
        // Follows check-ins for [scope]'s lifetime, subscribed before the constructor returns whatever the dispatcher:
        // the host builds this before it starts any check-in, so one that saves new credentials can't be missed.
        // Each success compares what is saved with [current], not with the last one it saw: if [load] put an older
        // read back (it raced a recovery's save), the next success corrects it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            Credentials.changes(successes, ::read).collect { saved ->
                if (saved != current) {
                    current = saved
                    reevaluate()
                }
            }
        }
    }

    /** Re-reads the saved credentials now (after the first sync, which may have just enrolled the device). */
    suspend fun load() {
        current = read().let { (id, secret) ->
            if (id.isNullOrBlank() || secret.isNullOrBlank()) null else Credentials(id, secret)
        }
    }

    /** Holds the socket when [hot], drops it otherwise. Without credentials, re-reads them once (enrollment may have
     *  finished elsewhere, in the worker or the heartbeat) and tries again if they appeared. */
    fun reevaluate() {
        val saved = current
        if (saved == null) {
            scope.launch {
                load()
                if (current != null) reevaluate()
            }
            return
        }
        if (hot()) transport.start(saved.deviceId, saved.secret, onWake) else transport.stop()
    }

    private suspend fun read(): Pair<String?, String?> = identity.current() to identity.secret()
}
