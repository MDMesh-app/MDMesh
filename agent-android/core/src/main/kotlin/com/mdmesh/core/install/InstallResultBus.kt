package com.mdmesh.core.install

import android.content.pm.PackageInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide rendezvous between the (app-side, manifest-registered) install result
 * receiver and [InstallManager].
 *
 * `PackageInstaller.Session.commit` reports its outcome asynchronously via a
 * `PendingIntent` broadcast to a **manifest-registered, explicit-intent, non-exported**
 * receiver (Android 14+ guardrail). That receiver lives in `:app` and calls [publish];
 * the coroutine driving the install awaits the matching result via [await], correlated by
 * the PackageInstaller session id.
 *
 * Correlation is a per-session [CompletableDeferred] kept in a map, so it is **race-free
 * in both directions**: whether the broadcast (publish) arrives before or after [await]
 * subscribes, the result is never lost. (An earlier `SharedFlow(replay=0)` design dropped
 * a result that beat the awaiter — which could hang an install — hence this map.) As a
 * `@Singleton` it is the same instance for publisher and awaiter.
 */
@Singleton
class InstallResultBus @Inject constructor() {

    /** A single install/uninstall outcome reported by the result receiver. */
    data class InstallResultEvent(
        val sessionId: Int,
        /** A `PackageInstaller.STATUS_*` value. */
        val status: Int,
        /** Optional human-readable detail (`EXTRA_STATUS_MESSAGE`). */
        val message: String?,
    )

    private val lock = Any()
    private val pending = mutableMapOf<Int, CompletableDeferred<InstallResultEvent>>()
    /** Sessions whose wait timed out: their late result is dropped instead of waiting for the next use of the id. */
    private val abandoned = mutableSetOf<Int>()

    private fun slotFor(sessionId: Int): CompletableDeferred<InstallResultEvent> =
        synchronized(lock) { pending.getOrPut(sessionId) { CompletableDeferred() } }

    /** Called by the result receiver for every committed session's outcome. */
    fun publish(sessionId: Int, status: Int, message: String?) {
        val slot = synchronized(lock) {
            if (abandoned.remove(sessionId)) return
            pending.getOrPut(sessionId) { CompletableDeferred() }
        }
        slot.complete(InstallResultEvent(sessionId, status, message))
    }

    /**
     * Call before handing [sessionId] to the platform. Forgets anything left for a reused id (uninstall derives it
     * from the package): an abandoned mark, which would drop this operation's own fast result, and a slot a late
     * result completed, which would hand that stale result to this wait.
     */
    fun expect(sessionId: Int) {
        synchronized(lock) {
            abandoned.remove(sessionId)
            pending[sessionId] = CompletableDeferred()
        }
    }

    /** Suspends until the result for [sessionId] is published, then returns it. */
    suspend fun await(sessionId: Int): InstallResultEvent {
        val slot = slotFor(sessionId)
        try {
            return slot.await()
        } finally {
            synchronized(lock) { if (pending[sessionId] === slot) pending.remove(sessionId) }
        }
    }

    /**
     * Like [await], but returns null when no result arrives within [timeoutMs]. A broadcast the platform never
     * delivers (the process died and restarted mid-commit, an OEM quirk) must not suspend the caller forever: an
     * install runs under the check-in mutex, so an endless wait would stop every later check-in.
     */
    suspend fun await(sessionId: Int, timeoutMs: Long): InstallResultEvent? {
        val slot = slotFor(sessionId)
        withTimeoutOrNull(timeoutMs) { slot.await() }
        // On a timeout, dropping the slot and marking the id happen under one lock, so a result published meanwhile
        // either completed this slot (and is returned) or finds the mark and is dropped: it never completes a fresh
        // slot for the next operation on this id.
        val timedOut = synchronized(lock) {
            if (pending[sessionId] === slot) pending.remove(sessionId)
            (!slot.isCompleted).also { if (it) abandoned.add(sessionId) }
        }
        return if (timedOut) null else slot.await()
    }

    companion object {
        /**
         * Explicit broadcast action used for the install-result `PendingIntent`.
         * The `:app` manifest receiver is registered for this action (`exported="false"`).
         */
        const val ACTION = "com.mdmesh.INSTALL_RESULT"

        /** Int extra carrying the PackageInstaller session id (the PendingIntent request code). */
        const val EXTRA_SESSION_ID = "com.mdmesh.extra.SESSION_ID"

        /** Reused from the platform: int `PackageInstaller.STATUS_*` value. */
        const val EXTRA_STATUS = PackageInstaller.EXTRA_STATUS

        /** Reused from the platform: optional status detail string. */
        const val EXTRA_STATUS_MESSAGE = PackageInstaller.EXTRA_STATUS_MESSAGE
    }
}
