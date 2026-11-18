package com.mdmesh.core.store

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.EventType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent's DataStore files, each opened once for the process (DataStore refuses a second instance on a file), so
 * a file the storage damaged (#220: a brownout on budget eMMC) is recovered on its next read instead of failing every
 * read with "Unable to parse preferences proto.". The only place DataStore files are opened (StoreFactoryOnlyTest).
 *
 * Each store is written to `<name>_mirror` and then to its own file ([MirroredDataStore]), and every
 * write records how many entries it holds ([Integrity]). A primary is damaged when it can't be parsed (DataStore's
 * corruption handler), or when it parses but is short (an empty or cut-off file parses as fewer entries): it is then
 * restored from its mirror, or failing that from the store's own rule below. Every restore is recorded as a
 * [EventType.STORAGE_RECOVERED] event and marks the configuration stale, so the server re-sends it and the device
 * converges on what the server holds. A damaged mirror is copied again from its primary, quietly.
 *
 * The files are the ones the `preferencesDataStore` delegates used ([preferencesDataStoreFile]), so an upgrade reads
 * what an older agent wrote; [check] then seals and mirrors it. The stores over it come from Hilt
 * ([com.mdmesh.core.di.StoreModule]), or from [stores] where there is no injection (the admin receiver).
 */
@Singleton
class AgentDataStores(
    private val file: (String) -> File,
    private val configStore: ConfigStateStore,
    private val events: EventSink,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        configStore: ConfigStateStore,
        events: EventLog,
    ) : this({ name -> context.preferencesDataStoreFile(name) }, configStore, events)

    // Recovery rule per store, applied to a damaged primary (the first that has something wins):

    /** The server-issued device id and secret ([DeviceIdStore]). Recovery: mirror, else empty (re-enrollment). */
    val identity: MirroredDataStore = mirrored(IDENTITY)

    /** The enrollment token and attempt ([EnrollTokenStore]). Recovery: mirror, else empty. */
    val enroll: MirroredDataStore = mirrored(ENROLL)

    /**
     * The kiosk the device is in ([DataStoreKioskStateStore]). Recovery: mirror (also when it says "no kiosk": an
     * exit, on-device or remote, is kept), else the kiosk the configuration sets ([configuredKiosk]), so a device
     * whose configuration sets a kiosk fails closed into it, else no kiosk. The re-sent configuration settles the rest.
     */
    val kiosk: MirroredDataStore = mirrored(KIOSK) {
        configStore.configuredKiosk()?.let { DataStoreKioskStateStore.prefsOf(it) to FROM_CONFIGURATION }
    }

    private val all = listOf(IDENTITY to identity, ENROLL to enroll, KIOSK to kiosk)

    /**
     * Reads every store now (one that is damaged is recovered here, at start, rather than when a command needs it),
     * seals it and brings its mirror up to date: an agent upgraded from one without mirrors gets them here.
     *
     * @return the stores that could not be read (a storage error, logged); their reads retry on their own.
     */
    suspend fun check(): List<String> = all.mapNotNull { (name, store) ->
        try {
            store.updateData { it }
            null
        } catch (e: IOException) {
            warn("$name unreadable at start", e)
            name
        }
    }

    private fun mirrored(
        name: String,
        fallback: () -> Pair<Preferences, String>? = { null },
    ): MirroredDataStore {
        lateinit var store: MirroredDataStore
        val mirror = open(name + MIRROR_SUFFIX) {
            // The primary is the truth: nothing is lost, the mirror is copied again from it once it's read.
            warn("$name mirror is corrupt; copied again from the primary")
            scope.launch { store.syncMirror() }
            emptyPreferences()
        }
        val restore: suspend () -> Preferences = { restore(name, mirror, fallback) }
        store = MirroredDataStore(open(name, restore), mirror, name, restore)
        return store
    }

    /** What a damaged primary [name] is replaced by, recorded. */
    private suspend fun restore(
        name: String,
        mirror: DataStore<Preferences>,
        fallback: () -> Pair<Preferences, String>?,
    ): Preferences {
        val (prefs, source) = readMirror(mirror, name)?.let { it to FROM_MIRROR }
            ?: runCatching(fallback).onFailure { warn("$name fallback failed", it) }.getOrNull()
            ?: (emptyPreferences() to FROM_EMPTY)
        recovered(name, source)
        return Integrity.sealed(prefs)
    }

    /** The mirror's contents when it was written by this agent and is whole ([Integrity.isUsable]). */
    private suspend fun readMirror(mirror: DataStore<Preferences>, name: String): Preferences? = try {
        mirror.data.first().takeIf(Integrity::isUsable)
    } catch (e: IOException) {
        warn("$name mirror unreadable while recovering", e)
        null
    }

    private fun open(name: String, recover: suspend () -> Preferences): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // DataStore's handler isn't suspending, so the mirror's read (another file, another DataStore) blocks
            // the reading thread for that one small read, once per corruption.
            corruptionHandler = ReplaceFileCorruptionHandler { e ->
                warn("$name is corrupt, recovering", e)
                runBlocking { recover() }
            },
            scope = scope,
            produceFile = { file(name) },
        )

    /** Never throws: a recovery must not fail on its bookkeeping. */
    private fun recovered(name: String, source: String) {
        warn("$name recovered from $source")
        runCatching { events.record(EventType.STORAGE_RECOVERED, "$name from $source") }
            .onFailure { warn("recording the recovery of $name failed", it) }
        runCatching { configStore.markStale() }
            .onFailure { warn("marking the configuration stale after recovering $name failed", it) }
    }

    /** The stores where there is no injection (the admin receiver, provisioning): the same instances Hilt gives. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Stores {
        fun deviceIdStore(): DeviceIdStore
        fun enrollTokenStore(): EnrollTokenStore
        fun kioskStateStore(): KioskStateStore
    }

    companion object {
        const val IDENTITY = "mdm_identity"
        const val ENROLL = "mdm_enroll"
        const val KIOSK = "mdm_kiosk"
        const val MIRROR_SUFFIX = "_mirror"

        const val FROM_MIRROR = "mirror"
        const val FROM_CONFIGURATION = "configuration"
        const val FROM_EMPTY = "empty"

        fun stores(context: Context): Stores =
            EntryPointAccessors.fromApplication(context.applicationContext, Stores::class.java)
    }
}

/**
 * Each store's record of how many entries it holds, written with every write. A file cut short parses (protobuf has
 * no end marker): cut at zero it is empty, cut between entries it holds fewer. Either way the count is gone or more
 * than the entries left, whichever entries were lost, so the file is known to be damaged. More entries than the count
 * is an older agent (a downgrade) that added keys and kept the count: the file is kept and counted again.
 */
internal object Integrity {
    val ENTRIES = intPreferencesKey("__mdmesh_entries")

    /** [p] with its count. */
    fun sealed(p: Preferences): Preferences {
        val m = p.toMutablePreferences()
        m.remove(ENTRIES)
        m[ENTRIES] = m.asMap().size
        return m.toPreferences()
    }

    /** Written by this agent (counted). */
    fun isCounted(p: Preferences): Boolean = p[ENTRIES] != null

    /** Counted, but fewer entries than the count: cut short. */
    fun isShort(p: Preferences): Boolean = p[ENTRIES]?.let { p.asMap().size - 1 < it } ?: false

    /** A copy to restore from: counted and not short. */
    fun isUsable(p: Preferences): Boolean = isCounted(p) && !isShort(p)
}

/**
 * A DataStore whose every write is sealed ([Integrity]) and written to a mirror file first, then to the primary, so
 * a damaged primary is restored ([AgentDataStores]) to the newest state ever written: the mirror never lags. A write
 * cut off between the files leaves the primary older but intact, and it is kept: the write never completed (its
 * caller got no answer), and the next write or [AgentDataStores.check] copies the primary to the mirror again.
 * Writes run one at a time under one lock. A mirror that can't be written fails the write, before the primary is
 * touched, so the mirror can never fall behind.
 *
 * A primary that reads short, or uncounted while its mirror is counted (cut before its count), is damaged: reads and
 * writes see it [restore]d first, inside the primary's own update, so it is restored once however many read it.
 * Uncounted with no counted mirror is an install from before counts (0.5.0), kept as it is.
 */
class MirroredDataStore internal constructor(
    private val primary: DataStore<Preferences>,
    private val mirror: DataStore<Preferences>,
    private val name: String,
    private val restore: suspend () -> Preferences,
) : DataStore<Preferences> {
    private val writeLock = Mutex()

    override val data: Flow<Preferences> = primary.data.map { if (damaged(it)) primary.updateData(::intact) else it }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        writeLock.withLock {
            val next = Integrity.sealed(transform(data.first()))
            mirror.updateData { next }
            primary.updateData { next }
        }

    private suspend fun intact(current: Preferences): Preferences = if (damaged(current)) restore() else current

    private suspend fun damaged(p: Preferences): Boolean = when {
        Integrity.isCounted(p) -> Integrity.isShort(p)
        else -> try {
            Integrity.isUsable(mirror.data.first())
        } catch (e: IOException) {
            warn("$name mirror unreadable; the primary is taken as it is", e)
            false
        }
    }

    /** Copies the primary's current value to the mirror (a damaged primary is restored first, never copied). */
    suspend fun syncMirror() {
        try {
            writeLock.withLock {
                val now = data.first()
                mirror.updateData { now }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            warn("$name mirror not updated", e)
        }
    }
}

private const val TAG = "AgentDataStores"

/** Best effort: the JVM unit tests have no android.util.Log. */
private fun warn(message: String, e: Throwable? = null) {
    runCatching { Log.w(TAG, message, e) }
}
