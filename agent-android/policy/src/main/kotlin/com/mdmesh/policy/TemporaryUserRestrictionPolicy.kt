package com.mdmesh.policy

/**
 * Process-local coordination of an app restriction and managed operations.
 * Platform access is injected so failure and concurrency behavior can be tested without Android.
 * Explicit changes during an exception fail for retry; they are never reported as applied early.
 * Process death does not execute finally. Persisted config handles boot/self-update separately.
 */
internal open class TemporaryUserRestrictionPolicy(
    final override val capabilityKey: String,
    private val supported: () -> Boolean,
    private val isBlocked: () -> Boolean,
    private val setBlocked: (Boolean) -> Unit,
) : ManagedAppPolicy {
    private val lock = Any()
    private var active = 0
    private var restoreBlocked = false

    override fun isSupported(): Boolean = supported()

    override fun setEnabled(enabled: Boolean): PolicyOutcome = synchronized(lock) {
        if (!isSupported()) return@synchronized PolicyOutcome.Unsupported
        if (active > 0) {
            return@synchronized PolicyOutcome.Failed("$capabilityKey managed operation in progress; retry policy apply")
        }
        runCatching { writeBlocked(!enabled); PolicyOutcome.Applied }
            .getOrElse { PolicyOutcome.Failed(it.message ?: "$capabilityKey setEnabled failed") }
    }

    override suspend fun <T> withAllowed(operation: suspend () -> T): T {
        beginException()
        // Capture the result (including cancellation), restore synchronously, then propagate it.
        val result = runCatching { operation() }
        val restoration = runCatching { endException() }
        result.exceptionOrNull()?.let { error ->
            restoration.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        restoration.getOrThrow()
        return result.getOrThrow()
    }

    private fun beginException() = synchronized(lock) {
        check(isSupported()) { "$capabilityKey is not supported" }
        if (active == 0) {
            restoreBlocked = isBlocked()
            runCatching { writeBlocked(false) }.onFailure { error ->
                // A failed clear may already have changed the OS. Restore before returning.
                runCatching { writeBlocked(restoreBlocked) }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
        active++
    }

    private fun endException() = synchronized(lock) {
        check(active > 0) { "No active $capabilityKey exception" }
        active--
        if (active == 0) writeBlocked(restoreBlocked)
    }

    private fun writeBlocked(blocked: Boolean) {
        setBlocked(blocked)
        check(isBlocked() == blocked) { "$capabilityKey restriction did not converge" }
    }
}
