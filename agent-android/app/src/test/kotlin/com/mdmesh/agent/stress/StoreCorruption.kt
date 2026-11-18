package com.mdmesh.agent.stress

import android.content.Context
import com.mdmesh.core.store.AgentDataStores
import com.mdmesh.core.store.DataStoreKioskStateStore
import com.mdmesh.core.store.DeviceIdStore
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.EventType
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.TelemetryEventDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/**
 * #220 on the device's own files: what a brownout leaves (a primary DataStore file the storage zero-filled), with or
 * without the mirror the agent writes next to it. Written before the app first reads the store.
 */
internal class StoreCorruption(private val app: Context) {
    private val dir get() = File(app.filesDir, "datastore").also { it.mkdirs() }

    /** The primary of [name] is zero-filled; its mirror holds what [write] stores, or nothing. */
    fun corrupt(name: String, write: (suspend (AgentDataStores) -> Unit)? = null) {
        File(dir, "$name.preferences_pb").writeBytes(ByteArray(ZEROED_BYTES))
        val mirror = File(dir, "$name${AgentDataStores.MIRROR_SUFFIX}.preferences_pb")
        if (write == null) mirror.delete() else mirror.writeBytes(bytesOf(name, write))
    }

    fun corruptKiosk(mirror: KioskApplyPayload?) = when (mirror) {
        null -> corrupt(AgentDataStores.KIOSK)
        else -> corrupt(AgentDataStores.KIOSK) { DataStoreKioskStateStore(it.kiosk).save(mirror) }
    }

    fun corruptIdentity(mirrorId: String?) = when (mirrorId) {
        null -> corrupt(AgentDataStores.IDENTITY)
        else -> corrupt(AgentDataStores.IDENTITY) { DeviceIdStore(it.identity).saveCredentials(mirrorId, "s") }
    }

    /** The store [name] can't be read at all (an I/O error, not corruption): its file is a directory. */
    fun unreadable(name: String) {
        File(dir, "$name.preferences_pb").mkdirs()
    }

    /** The recoveries recorded for the next check-in. */
    fun recoveries(): List<String?> =
        EventLog(app).drain().filter { it.type == EventType.STORAGE_RECOVERED }.map { it.detail }

    /** The file DataStore writes for [name] after [write], made in a scratch directory. */
    private fun bytesOf(name: String, write: suspend (AgentDataStores) -> Unit): ByteArray {
        val scratch = Files.createTempDirectory("store").toFile()
        val job = Job()
        try {
            runBlocking {
                val stores = AgentDataStores(
                    { File(scratch, "$it.preferences_pb") }, InMemoryConfigStateStore(), NoEvents,
                    CoroutineScope(Dispatchers.IO + job),
                )
                write(stores)
                job.cancelAndJoin()
            }
            return File(scratch, "$name.preferences_pb").readBytes()
        } finally {
            scratch.deleteRecursively()
        }
    }

    private object NoEvents : EventSink {
        override fun record(type: String, detail: String?) = Unit
        override fun drain(): List<TelemetryEventDto> = emptyList()
        override fun restore(events: List<TelemetryEventDto>) = Unit
    }

    private companion object {
        const val ZEROED_BYTES = 4096
    }
}
