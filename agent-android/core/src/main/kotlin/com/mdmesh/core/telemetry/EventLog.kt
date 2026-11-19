package com.mdmesh.core.telemetry

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.mdmesh.proto.EventType
import com.mdmesh.proto.TelemetryEventDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Drain/restore contract for buffered events, so the Android-free sync logic (and tests) don't
 *  depend on the persistent store. Implemented by [EventLog]. */
interface EventSink {
    fun record(type: String, detail: String? = null)
    fun drain(): List<TelemetryEventDto>
    fun restore(events: List<TelemetryEventDto>)
    /** The check-in carrying the last [drain] succeeded. */
    fun delivered() {}
}

/**
 * Persistent buffer of device lifecycle events. SharedPreferences-backed so events survive offline
 * AND process restart. Capped at [CAP] to avoid unbounded growth: a full buffer drops its oldest events and counts them
 * in one [EventType.EVENTS_DROPPED] marker at the front, so the timeline shows the gap. Delivery is at least once: a
 * process death after the server took a batch but before [delivered] sends it again.
 * Lightweight enough to construct directly from a BroadcastReceiver (no Hilt) as well as via DI.
 */
@Singleton
class EventLog internal constructor(private val store: Store) : EventSink {

    /** Where events are kept. [put] applies all its changes in one write, visible at once, on disk later. */
    interface Store {
        fun get(key: String): String?
        fun put(vararg changes: Pair<String, String?>)
        /** Returns once everything [put] so far is on disk. */
        fun flush()
    }

    private class PrefsStore(context: Context) : Store {
        private val prefs = context.getSharedPreferences("mdm_events", Context.MODE_PRIVATE)
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(vararg changes: Pair<String, String?>) = edit(changes).apply()

        // An empty commit(): documented to block until every outstanding apply() is written, then itself.
        override fun flush() {
            prefs.edit().commit()
        }

        private fun edit(changes: Array<out Pair<String, String?>>) = prefs.edit().apply {
            for ((key, value) in changes) if (value == null) remove(key) else putString(key, value)
        }
    }

    @Inject constructor(@ApplicationContext context: Context) : this(PrefsStore(context))

    // One lock for the process, not per instance: the receivers build their own EventLog on the same prefs file
    // while the check-in drains the injected one, and a per-instance lock let their read-modify-writes interleave
    // (events lost).
    override fun record(type: String, detail: String?) = synchronized(LOCK) {
        val list = load(KEY).toMutableList()
        list.add(TelemetryEventDto(type, System.currentTimeMillis(), detail))
        store.put(KEY to encode(cap(list)))
    }

    // drain/restore/delivered run in the check-in, off the main thread, so they wait for the disk before returning: a
    // process death right after can neither lose the batch nor send a confirmed one again. They wait outside the
    // lock, so a record() on the main thread (receivers, the kiosk) never waits for the disk; its own write stays
    // asynchronous.

    /**
     * Take the oldest [MAX_BATCH] events for the check-in about to run; the rest wait for the next ones. The batch
     * stays stored (in flight) until [delivered] or [restore], like [com.mdmesh.core.sync.PendingResults], so a process
     * death mid-check-in sends it again rather than losing it. Whatever a dead process left in flight is older, so it
     * goes first.
     */
    override fun drain(): List<TelemetryEventDto> {
        val batch = synchronized(LOCK) {
            val all = cap(load(IN_FLIGHT) + load(KEY))
            all.take(MAX_BATCH).also { batch ->
                store.put(IN_FLIGHT to encodeOrNull(batch), KEY to encodeOrNull(all.drop(MAX_BATCH)))
            }
        }
        store.flush()
        return batch
    }

    /** The check-in failed: put the drained [events] back in front of anything recorded since. */
    override fun restore(events: List<TelemetryEventDto>) {
        synchronized(LOCK) {
            store.put(KEY to encodeOrNull(cap(events + load(KEY))), IN_FLIGHT to null)
        }
        store.flush()
    }

    override fun delivered() {
        synchronized(LOCK) { store.put(IN_FLIGHT to null) }
        store.flush()
    }

    /** Everything kept, in flight included, oldest first; for tests, which must not take a batch away. */
    @VisibleForTesting
    fun buffered(): List<TelemetryEventDto> = synchronized(LOCK) { load(IN_FLIGHT) + load(KEY) }

    private fun load(key: String): List<TelemetryEventDto> = decode(store.get(key))

    companion object {
        private val LOCK = Any()
        /** Buffered events. The key older agents used; kept so an upgrade sends what they buffered. */
        private const val KEY = "events"
        /** Drained for a check-in that has not been confirmed yet. */
        private const val IN_FLIGHT = "events_in_flight"
        /** Events kept, the [EventType.EVENTS_DROPPED] marker included. */
        const val CAP = 500
        /**
         * Events per check-in. A server stores only the first 100 (500 from 0.5.2 on) and ignores the rest without
         * saying so, so a bigger batch would lose the rest once [delivered].
         */
        const val MAX_BATCH = 100
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Keep the most recent [CAP] - 1 events behind one [EventType.EVENTS_DROPPED] marker counting what was dropped
         * (earlier markers' counts included), dated like the newest dropped event.
         */
        fun cap(list: List<TelemetryEventDto>): List<TelemetryEventDto> {
            if (list.size <= CAP) return list
            val dropped = list.dropLast(CAP - 1)
            val count = dropped.sumOf { if (it.type == EventType.EVENTS_DROPPED) droppedCount(it) else 1 }
            val marker = TelemetryEventDto(
                EventType.EVENTS_DROPPED,
                dropped.last().ts,
                "$count older ${if (count == 1) "event" else "events"} dropped: the device keeps at most $CAP " +
                    "while it can't reach the server",
            )
            return listOf(marker) + list.takeLast(CAP - 1)
        }

        /** How many events an [EventType.EVENTS_DROPPED] marker counts. */
        fun droppedCount(marker: TelemetryEventDto): Int =
            marker.detail?.substringBefore(' ')?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        fun encode(list: List<TelemetryEventDto>): String =
            json.encodeToString(ListSerializer(TelemetryEventDto.serializer()), list)

        private fun encodeOrNull(list: List<TelemetryEventDto>): String? =
            list.takeIf { it.isNotEmpty() }?.let(::encode)

        fun decode(s: String?): List<TelemetryEventDto> =
            if (s.isNullOrBlank()) emptyList()
            else runCatching {
                json.decodeFromString(ListSerializer(TelemetryEventDto.serializer()), s)
            }.getOrDefault(emptyList())
    }
}
