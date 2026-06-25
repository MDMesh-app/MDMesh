package com.mdmesh.core.sync

import com.mdmesh.proto.CommandResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory buffer of command results awaiting delivery. Results are posted on the
 * *next* check-in (the request carries acks for the previous batch).
 *
 * Deliberately in-memory: if the process dies before acks are delivered, the server
 * simply never marks those commands done and re-sends them; handlers are idempotent
 * (the sync loop reconciles), so the outcome self-heals. Persisting acks is a possible
 * later optimisation, not a correctness requirement.
 */
@Singleton
class PendingResults @Inject constructor() {

    private val buffer = mutableListOf<CommandResult>()

    @Synchronized
    fun add(results: List<CommandResult>) {
        buffer.addAll(results)
    }

    /** Take and clear the current buffer for delivery. */
    @Synchronized
    fun drain(): List<CommandResult> {
        val snapshot = buffer.toList()
        buffer.clear()
        return snapshot
    }

    /** Put drained results back (front) when delivery failed, so they retry next cycle. */
    @Synchronized
    fun restore(results: List<CommandResult>) {
        buffer.addAll(0, results)
    }
}
