package com.mdmesh.policy

import java.util.concurrent.ConcurrentHashMap

/**
 * Rate-limited logging for [TogglePolicyResolver] probe errors. Policies are resolved on every check-in, apply and
 * heartbeat, so an error that persists would otherwise log a stack trace on each read. A key's error is logged on its
 * first occurrence, when its exception class changes, and again at most once per [intervalMs]; its recovery is logged
 * once.
 *
 * @param log message plus the error (null for a recovery).
 */
class ProbeErrorLog(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (message: String, error: Throwable?) -> Unit,
) {
    private class Logged(val errorClass: String, val atMs: Long)

    private val logged = ConcurrentHashMap<String, Logged>()

    fun failed(key: String, error: Throwable) {
        val now = clock()
        val errorClass = error.javaClass.name
        val last = logged[key]
        if (last != null && last.errorClass == errorClass && now - last.atMs in 0 until intervalMs) return
        logged[key] = Logged(errorClass, now)
        log("policy check failed: $key", error)
    }

    fun recovered(key: String) {
        if (logged.remove(key) != null) log("policy check recovered: $key", null)
    }

    companion object {
        /** About one heartbeat. */
        const val DEFAULT_INTERVAL_MS: Long = 10L * 60L * 1000L
    }
}
