@file:Suppress("ForbiddenImport") // The one place DataStore files are opened (config/detekt/detekt.yml).

package com.mdmesh.core.store

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent's DataStore files, each opened once for the process (DataStore refuses a second instance on a file) and
 * each with a corruption handler, so a file the storage damaged (#220: a brownout on budget eMMC) is recovered on
 * its next read instead of failing every read with "Unable to parse preferences proto.".
 *
 * Each store is written to its file and then copied to `<name>_mirror` next to it ([MirroredDataStore]). A primary
 * that can't be parsed is replaced by its mirror; with no usable mirror the kiosk store falls back to the kiosk the
 * configuration sets (fail closed: a kiosk device stays a kiosk), and anything else starts empty. Every recovery is
 * recorded as a [EventType.STORAGE_RECOVERED] event and marks the configuration stale, so the server re-sends it and
 * the device converges on what the server holds.
 *
 * The files are the ones the `preferencesDataStore` delegates used ([preferencesDataStoreFile]), so an upgrade reads
 * what an older agent wrote. The stores over it come from Hilt ([com.mdmesh.core.di.StoreModule]), or from
 * [stores] where there is no injection (the admin receiver).
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

    // Recovery rule per store, applied when its file can't be parsed (the first that has something wins):

    /** The server-issued device id and secret ([DeviceIdStore]). Recovery: mirror, else empty (re-enrollment). */
    val identity: MirroredDataStore = mirrored(IDENTITY)

    /** The enrollment token and attempt ([EnrollTokenStore]). Recovery: mirror, else empty. */
    val enroll: MirroredDataStore = mirrored(ENROLL)

    /**
     * The kiosk the device is in ([DataStoreKioskStateStore]). Recovery: mirror, else the kiosk the configuration
     * sets ([configuredKiosk]), else empty (no kiosk). An empty mirror counts as no mirror, so a device whose
     * configuration sets a kiosk fails closed into it; the re-sent configuration settles the rest.
     */
    val kiosk: MirroredDataStore = mirrored(KIOSK) {
        configStore.configuredKiosk()?.let { DataStoreKioskStateStore.prefsOf(it) to FROM_CONFIGURATION }
    }

    private val all = listOf(IDENTITY to identity, ENROLL to enroll, KIOSK to kiosk)

    /**
     * Reads every store now (one that is corrupt is recovered here, at start, rather than when a command needs it)
     * and brings each mirror up to date, which also seeds the mirrors of an agent upgraded from one without them.
     *
     * @return the stores that could not be read (a storage error, logged); their reads retry on their own.
     */
    suspend fun check(): List<String> = all.mapNotNull { (name, store) ->
        try {
            store.data.first()
            store.syncMirror()
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
        val mirrorName = name + MIRROR_SUFFIX
        val mirror = open(mirrorName) {
            recovered(mirrorName, FROM_EMPTY)
            emptyPreferences()
        }
        val primary = open(name) {
            val (prefs, source) = readMirror(mirror, name)?.let { it to FROM_MIRROR }
                ?: runCatching(fallback).onFailure { warn("$name fallback failed", it) }.getOrNull()
                ?: (emptyPreferences() to FROM_EMPTY)
            recovered(name, source)
            prefs
        }
        return MirroredDataStore(primary, mirror, name)
    }

    /** The mirror's contents, or null when it is empty or can't be read (the primary then falls back further). */
    private suspend fun readMirror(mirror: DataStore<Preferences>, name: String): Preferences? = try {
        mirror.data.first().takeIf { it.asMap().isNotEmpty() }
    } catch (e: IOException) {
        warn("$name mirror unreadable while recovering", e)
        null
    }

    private fun open(name: String, recover: suspend () -> Preferences): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // DataStore's handler isn't suspending, so the mirror's read (another file, another DataStore, whose own
            // work runs on its own scope) blocks the reading thread for that one small read, once per corruption.
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
 * A DataStore whose every write is then copied to a mirror file, so a primary the storage corrupted can be restored
 * ([AgentDataStores]). The mirror always gets the primary's latest value (read under one lock), so two writes racing
 * can't leave it with the older one. Copying is best effort: a failed copy is logged and repeated by the next write
 * or [AgentDataStores.check]; the primary stays the truth.
 */
class MirroredDataStore internal constructor(
    private val primary: DataStore<Preferences>,
    private val mirror: DataStore<Preferences>,
    private val name: String,
) : DataStore<Preferences> {
    private val mirrorLock = Mutex()

    override val data = primary.data

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        primary.updateData(transform).also { syncMirror() }

    /** Copies the primary's current value to the mirror. */
    suspend fun syncMirror() {
        try {
            mirrorLock.withLock {
                val now = primary.data.first()
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
