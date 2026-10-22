package com.mdmesh.core.location

/**
 * Lets something happen at most once per [windowMs], timed on [elapsedMs], a clock that only moves forward (Android's
 * `elapsedRealtime`), so a clock change can't make it run more often. The first use is always allowed. In memory: a
 * new process starts afresh.
 */
class Throttle(private val windowMs: Long, private val elapsedMs: () -> Long) {
    @Volatile private var lastUsedAt: Long? = null

    /** True, and the window starts, when the last use is at least [windowMs] ago (or there was none). */
    fun tryUse(): Boolean {
        val now = elapsedMs()
        val last = lastUsedAt
        val allowed = last == null || now - last >= windowMs
        if (allowed) lastUsedAt = now
        return allowed
    }
}
