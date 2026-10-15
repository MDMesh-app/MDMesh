package com.mdmesh.core.sync

import android.content.Context
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.CommandStatus
import com.mdmesh.proto.ProtocolJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Buffer of command results awaiting delivery. Results are posted on the *next* check-in (the request carries acks
 * for the previous batch).
 *
 * Persisted, because the server never re-sends a delivered command: a result lost with the process (killed, OEM
 * task killer, reboot) leaves that command "delivered" until it expires, and the console never learns how it ended.
 * Drained results stay persisted until [delivered] confirms the check-in that carried them succeeded, so a process
 * death mid-check-in re-sends them too (the server tolerates a repeated ack).
 */
@Singleton
class PendingResults(private val backup: Backup = Backup.None) {

    /** Where the buffer survives process death. [write]s must be applied in order. */
    interface Backup {
        fun read(): String?
        fun write(raw: String?)

        object None : Backup {
            override fun read(): String? = null
            override fun write(raw: String?) = Unit
        }
    }

    /**
     * SharedPreferences: one small value, read once. Callers run on the main thread (the check-in service's scope), so
     * each write is committed to disk on one background thread, in order, rather than blocking the caller.
     */
    private class PrefsBackup(private val context: Context) : Backup {
        private val prefs by lazy { context.getSharedPreferences("mdm_pending_results", Context.MODE_PRIVATE) }
        private val writer = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pending-results").apply { isDaemon = true }
        }
        override fun read(): String? = prefs.getString(KEY, null)
        override fun write(raw: String?) {
            writer.execute { prefs.edit().apply { if (raw == null) remove(KEY) else putString(KEY, raw) }.commit() }
        }
    }

    @Inject constructor(@ApplicationContext context: Context) : this(PrefsBackup(context))

    private val buffer = mutableListOf<CommandResult>()
    /** Drained for the check-in in progress; kept persisted until [delivered] or [restore]. */
    private val inFlight = mutableListOf<CommandResult>()

    private var loaded = false

    /** Whatever a dead process left (in flight or not) is due on the next check-in. Read on first use, not at
     *  injection. */
    private fun load() {
        if (loaded) return
        loaded = true
        backup.read()?.let { raw ->
            runCatching { ProtocolJson.json.decodeFromString(SERIALIZER, raw) }.getOrNull()
                ?.let { buffer.addAll(0, it) }
        }
    }

    @Synchronized
    fun add(results: List<CommandResult>) {
        load()
        if (results.isEmpty()) return
        buffer.addAll(results.map(::bounded))
        persist()
    }

    /**
     * Take the next batch for delivery: from the front, at most [MAX_BATCH_RESULTS] results and [MAX_BATCH_CHARS] of
     * serialized JSON, but always at least one. The rest waits for the next check-in, so a backlog (e.g. results that
     * piled up offline) never turns into one request the server refuses and the agent resends forever.
     */
    @Synchronized
    fun drain(): List<CommandResult> {
        load()
        val batch = mutableListOf<CommandResult>()
        var chars = 0
        for (result in buffer) {
            val size = ProtocolJson.json.encodeToString(CommandResult.serializer(), result).length
            val full = batch.size >= MAX_BATCH_RESULTS || (batch.isNotEmpty() && chars + size > MAX_BATCH_CHARS)
            if (full) break
            batch += result
            chars += size
        }
        inFlight.addAll(batch)
        buffer.subList(0, batch.size).clear()
        return batch
    }

    /** Put drained results back (front) when delivery failed, so they retry next cycle. */
    @Synchronized
    fun restore(results: List<CommandResult>) {
        load()
        inFlight.removeAll(results.toSet())
        buffer.addAll(0, results)
        persist()
    }

    /**
     * The check-in carrying the drained results succeeded: forget the first [accepted] of them (all when null) and put
     * the ones the server did not take back in front, for the next check-in.
     */
    @Synchronized
    fun delivered(accepted: Int? = null) {
        if (inFlight.isEmpty()) return
        val rest = if (accepted == null) emptyList() else inFlight.drop(accepted.coerceAtLeast(0))
        inFlight.clear()
        buffer.addAll(0, rest)
        persist()
    }

    private fun persist() {
        val all = inFlight + buffer
        backup.write(if (all.isEmpty()) null else ProtocolJson.json.encodeToString(SERIALIZER, all))
    }

    companion object {
        /** Results per check-in: what the server takes at most (it reports how many it took). */
        const val MAX_BATCH_RESULTS = 1000
        /** Serialized results per check-in; the server refuses request bodies over 32 MiB. */
        const val MAX_BATCH_CHARS = 4 * 1024 * 1024
        /** A result detail longer than this (the server's own limit) is replaced by a short failure. */
        const val MAX_DETAIL_CHARS = 2 * 1024 * 1024

        private const val KEY = "results"
        private val SERIALIZER = ListSerializer(CommandResult.serializer())

        private fun bounded(result: CommandResult): CommandResult {
            val length = result.detail?.length ?: 0
            return if (length <= MAX_DETAIL_CHARS) {
                result
            } else {
                result.copy(status = CommandStatus.FAILED, detail = "result too large ($length chars)")
            }
        }
    }
}
