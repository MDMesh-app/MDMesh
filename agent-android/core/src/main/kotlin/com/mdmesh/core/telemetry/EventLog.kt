package com.mdmesh.core.telemetry

import android.content.Context
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
}

/**
 * Persistent buffer of device lifecycle events. SharedPreferences-backed so events survive offline
 * AND process restart (unlike the in-memory command-ack buffer). Capped to avoid unbounded growth.
 * Lightweight enough to construct directly from a BroadcastReceiver (no Hilt) as well as via DI.
 */
@Singleton
class EventLog @Inject constructor(@ApplicationContext context: Context) : EventSink {
    private val prefs = context.getSharedPreferences("mdm_events", Context.MODE_PRIVATE)

    @Synchronized
    override fun record(type: String, detail: String?) {
        val list = load().toMutableList()
        list.add(TelemetryEventDto(type, System.currentTimeMillis(), detail))
        save(cap(list))
    }

    @Synchronized
    override fun drain(): List<TelemetryEventDto> {
        val l = load()
        prefs.edit().remove(KEY).apply()
        return l
    }

    @Synchronized
    override fun restore(events: List<TelemetryEventDto>) {
        save(cap(events + load()))
    }

    private fun load(): List<TelemetryEventDto> = decode(prefs.getString(KEY, null))
    private fun save(list: List<TelemetryEventDto>) {
        prefs.edit().putString(KEY, encode(list)).apply()
    }

    companion object {
        private const val KEY = "events"
        private const val CAP = 500
        private val json = Json { ignoreUnknownKeys = true }

        /** Keep the most recent [CAP] events. */
        fun cap(list: List<TelemetryEventDto>): List<TelemetryEventDto> =
            if (list.size <= CAP) list else list.takeLast(CAP)

        fun encode(list: List<TelemetryEventDto>): String =
            json.encodeToString(ListSerializer(TelemetryEventDto.serializer()), list)

        fun decode(s: String?): List<TelemetryEventDto> =
            if (s.isNullOrBlank()) emptyList()
            else runCatching {
                json.decodeFromString(ListSerializer(TelemetryEventDto.serializer()), s)
            }.getOrDefault(emptyList())
    }
}
