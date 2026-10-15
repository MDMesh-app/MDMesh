package com.mdmesh.policy

/**
 * Process-local coordination of an app restriction and managed operations.
 * Platform access is injected so failure and concurrency behavior can be tested without Android.
 * Explicit changes during an exception fail for retry; they are never reported as applied early.
 * Nothing is written when the restriction is not set. Process death skips the restore, so the pre-lift value is
 * recorded in [store] first and put back by [restoreInterrupted] at the next check-in cycle or service start (boot and
 * self-update also re-apply the persisted config).
 */
internal open class TemporaryUserRestrictionPolicy(
    final override val capabilityKey: String,
    private val supported: () -> Boolean,
    private val isBlocked: () -> Boolean,
    private val setBlocked: (Boolean) -> Unit,
    private val store: LiftRestoreStore = InMemoryLiftRestoreStore(),
) : ManagedAppPolicy {
    private val lock = Any()
    private var active = 0
    private var restoreBlocked = false
    private var lifted = false

    override fun isSupported(): Boolean = supported()

    override fun setEnabled(enabled: Boolean): PolicyOutcome = synchronized(lock) {
        if (!isSupported()) return@synchronized PolicyOutcome.Unsupported
        if (active > 0) {
            return@synchronized PolicyOutcome.Failed("$capabilityKey managed operation in progress; retry policy apply")
        }
        runCatching {
            writeBlocked(!enabled)
            // An explicit value supersedes one left by an interrupted operation. A failed clear reports Failed, so the
            // server re-sends instead of the record re-blocking over this value at the next cycle.
            if (store.get(capabilityKey) != null) store.remove(capabilityKey)
            PolicyOutcome.Applied
        }.getOrElse { PolicyOutcome.Failed(it.message ?: "$capabilityKey setEnabled failed") }
    }

    override fun restoreInterrupted(): Boolean = synchronized(lock) {
        if (active > 0) return@synchronized false
        val blocked = store.get(capabilityKey) ?: return@synchronized false
        if (isSupported()) writeBlocked(blocked)
        store.remove(capabilityKey)
        true
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
        if (active == 0) lifted = liftIfBlocked()
        active++
    }

    /**
     * Lifts the restriction for the first of overlapping operations; false when there is nothing to lift. That case
     * touches neither the store nor the DPM (one read at most), so a device that does not use the block, or is no
     * longer Device Owner, gains no new way for an install (or the agent's own self-update) to fail.
     */
    private fun liftIfBlocked(): Boolean {
        val saved = store.get(capabilityKey)
        // An unreadable state is treated as "not blocked": the install then behaves as it did before this policy.
        val needsLift = isSupported() && (saved != null || runCatching { isBlocked() }.getOrDefault(false))
        if (!needsLift) return false
        // A value left by an interrupted operation is the real pre-lift state (the device may still be lifted).
        restoreBlocked = saved ?: true
        store.put(capabilityKey, restoreBlocked)
        runCatching { writeBlocked(false) }.onFailure { error ->
            // A failed clear may already have changed the OS. Restore before returning.
            runCatching { writeBlocked(restoreBlocked); store.remove(capabilityKey) }
                .exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        return true
    }

    private fun endException() = synchronized(lock) {
        check(active > 0) { "No active $capabilityKey exception" }
        active--
        if (active == 0 && lifted) {
            lifted = false
            writeBlocked(restoreBlocked)
            // Kept when the write throws: restoreInterrupted retries it. A failed clear of the record alone must not
            // fail a finished install: the record then equals the device state, so the next restore rewrites the same
            // value.
            runCatching { store.remove(capabilityKey) }
        }
    }

    private fun writeBlocked(blocked: Boolean) {
        setBlocked(blocked)
        check(isBlocked() == blocked) { "$capabilityKey restriction did not converge" }
    }
}
