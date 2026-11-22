package com.mdmesh.agent.stress

import android.content.Context
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
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

    /** The storage error is over: the kiosk store [unreadable] made holds [kiosk] again, as the agent writes it. */
    fun readableAgain(kiosk: KioskApplyPayload) {
        val bytes = bytesOf(AgentDataStores.KIOSK) { DataStoreKioskStateStore(it.kiosk).save(kiosk) }
        val primary = File(dir, "${AgentDataStores.KIOSK}.preferences_pb")
        check(primary.delete()) { "the unreadable store's directory is empty" }
        primary.writeBytes(bytes)
    }

    private val recorded = mutableListOf<String?>()

    /** The recoveries recorded so far (taken from the events for the next check-in, and kept here). */
    fun recoveries(): List<String?> {
        // Taken as a check-in takes them: a drained batch stays in flight (and comes again) until delivered.
        val log = EventLog(app)
        recorded += log.drain().filter { it.type == EventType.STORAGE_RECOVERED }.map { it.detail }
        log.delivered()
        return recorded.toList()
    }

    /** Runs the main looper until [done] holds (the stores deliver on real threads), failing after a timeout. */
    fun eventually(what: String, done: () -> Boolean) {
        repeat(POLL_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for $what")
    }

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
        override fun drain(max: Int): List<TelemetryEventDto> = emptyList()
        override fun restore(events: List<TelemetryEventDto>) = Unit
    }

    private companion object {
        const val ZEROED_BYTES = 4096
        const val POLL_ROUNDS = 500
        const val POLL_MS = 10L
    }
}
