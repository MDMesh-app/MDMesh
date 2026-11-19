package com.mdmesh.core.store

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.mdmesh.core.kiosk.ExitJournal
import com.mdmesh.core.kiosk.SharedPrefsExitJournal
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.kiosk.KioskController
import com.mdmesh.proto.EventType
import com.mdmesh.proto.KioskApplyPayload
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
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
    /**
     * The apps the device is locked to now, when it was left in a kiosk: lock task's allowlist, which only a kiosk
     * sets and every finished exit clears ([lockedAppsOf]).
     */
    private val lockedApps: () -> List<String>? = { null },
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        configStore: ConfigStateStore,
        events: EventLog,
        kioskController: KioskController,
    ) : this(
        { name -> context.preferencesDataStoreFile(name) }, configStore, events,
        lockedApps = {
            lockedAppsOf(kioskController.allowedPackages(), context.packageName) {
                SharedPrefsExitJournal(context).pending()
            }
        },
    )

    // Recovery rule per store, applied to a damaged primary (the first that has something wins):

    /** The server-issued device id and secret ([DeviceIdStore]). Recovery: mirror, else empty (re-enrollment). */
    val identity: MirroredDataStore = mirrored(IDENTITY)

    /** The enrollment token and attempt ([EnrollTokenStore]). Recovery: mirror, else empty. */
    val enroll: MirroredDataStore = mirrored(ENROLL)

    /**
     * The kiosk the device is in ([DataStoreKioskStateStore]). Recovery: mirror (also when it says "no kiosk": an
     * exit, on-device or remote, is kept), else the kiosk the configuration sets ([configuredKiosk]), else, when the
     * device is still locked to apps ([lockedApps]: a `kiosk.enter` kiosk not in the configuration), a kiosk of those
     * apps that only the server can end, else no kiosk. Fail closed: a power loss that takes both files never unpins a
     * kiosk device. The re-sent configuration settles the rest. Lock task's allowlist can only be read on API 26+
     * ([KioskController.allowedPackages]): below it that step finds nothing, and the configured kiosk is the last
     * evidence.
     */
    val kiosk: MirroredDataStore = mirrored(KIOSK) {
        configStore.configuredKiosk()?.let { DataStoreKioskStateStore.prefsOf(it) to FROM_CONFIGURATION }
            ?: lockedApps()?.let { DataStoreKioskStateStore.prefsOf(lockedKiosk(it)) to FROM_LOCK_TASK }
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
        val sealMark = file(name).resolveSibling(name + SEALED_SUFFIX)
        store = MirroredDataStore(open(name, restore), mirror, name, restore, sealMark)
        return store
    }

    /**
     * What a damaged primary [name] is replaced by, recorded. The mirror's copy as it is; a fallback is sealed one
     * generation above anything the mirror holds and written to the mirror too, so the two match and an older mirror
     * can never later be taken for the newer state.
     */
    private suspend fun restore(
        name: String,
        mirror: DataStore<Preferences>,
        fallback: () -> Pair<Preferences, String>?,
    ): Preferences {
        val mirrored = readMirror(mirror, name)
        if (mirrored != null && Integrity.isUsable(mirrored)) {
            recovered(name, FROM_MIRROR)
            return mirrored
        }
        val (prefs, source) = runCatching(fallback).onFailure { warn("$name fallback failed", it) }.getOrNull()
            ?: (emptyPreferences() to FROM_EMPTY)
        val restored = Integrity.sealed(prefs, (mirrored?.let(Integrity::generationOf) ?: 0L) + 1)
        try {
            mirror.updateData { restored }
        } catch (e: IOException) {
            warn("$name mirror not written after recovering from $source", e)
        }
        recovered(name, source)
        return restored
    }

    /** The mirror's contents, whatever they are; null when it can't be read. */
    private suspend fun readMirror(mirror: DataStore<Preferences>, name: String): Preferences? = try {
        mirror.data.first()
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

        /** Next to a store's file once this agent has written it whole ([MirroredDataStore]). */
        const val SEALED_SUFFIX = ".sealed"

        const val FROM_MIRROR = "mirror"
        const val FROM_CONFIGURATION = "configuration"
        const val FROM_EMPTY = "empty"
        const val FROM_LOCK_TASK = "lock task"

        /**
         * The apps of [allowlist] besides the agent's own, when the device was left in a kiosk; null when there are
         * none, or when an authorised exit was under way ([pending] untried, not failed): the device was on its way
         * out. An exit that failed or was already tried settles back in kiosk ([ExitJournal]), so it doesn't count,
         * and neither does a journal that can't be read. An allowlist that couldn't be read (null) fails closed: the
         * agent's own launcher, with no apps, until the server says otherwise.
         */
        internal fun lockedAppsOf(
            allowlist: List<String>?,
            ownPackage: String,
            pending: () -> ExitJournal.Pending?,
        ): List<String>? {
            val apps = allowlist?.minus(ownPackage) ?: emptyList()
            if (allowlist != null && apps.isEmpty()) return null
            val journal = runCatching(pending).onFailure { warn("exit journal unreadable; taken as no exit", it) }
            val exiting = journal.getOrNull()?.let { !it.failed && it.attempts == 0 } == true
            return apps.takeUnless { exiting }
        }

        /** A kiosk of the apps lock task allows, its exit left to the server (no PIN to guess). */
        internal fun lockedKiosk(apps: List<String>) =
            KioskApplyPayload(mode = "launcher", allowedPackages = apps, exitMode = "remote")

        fun stores(context: Context): Stores =
            EntryPointAccessors.fromApplication(context.applicationContext, Stores::class.java)
    }
}

/**
 * Each store's record of how many entries it holds, and of its generation (one more on every write), written with
 * every write. A file cut short parses (protobuf has no end marker): cut at zero it is empty, cut between entries it
 * holds fewer. Either way the count is gone or more than the entries left, whichever entries were lost, so the file
 * is known to be damaged. More entries than the count is an older agent (a downgrade) that added keys and kept the
 * count: the file is kept and counted again. The generation tells a mirror one write ahead of its primary.
 */
internal object Integrity {
    val ENTRIES = intPreferencesKey("__mdmesh_entries")
    val GENERATION = longPreferencesKey("__mdmesh_generation")
    private val MARKS: Set<Preferences.Key<*>> = setOf(ENTRIES, GENERATION)

    /** [p] with its count and [generation]. */
    fun sealed(p: Preferences, generation: Long = generationOf(p)): Preferences {
        val m = p.toMutablePreferences()
        MARKS.forEach { m.remove(it) }
        m[ENTRIES] = m.asMap().size
        m[GENERATION] = generation
        return m.toPreferences()
    }

    fun generationOf(p: Preferences): Long = p[GENERATION] ?: 0L

    private fun entries(p: Preferences): Int = p.asMap().keys.count { it !in MARKS }

    /** Written by this agent (counted). */
    fun isCounted(p: Preferences): Boolean = p[ENTRIES] != null

    /** Counted, but fewer entries than the count: cut short. */
    fun isShort(p: Preferences): Boolean = p[ENTRIES]?.let { entries(p) < it } ?: false

    /** A copy to restore from: counted and not short. */
    fun isUsable(p: Preferences): Boolean = isCounted(p) && !isShort(p)
}

/**
 * A DataStore whose every write is sealed ([Integrity]) and written to a mirror file first, then to the primary. The
 * mirror is the commit point, like a write-ahead log: once it is written (and its directory synced) the write has
 * happened. The primary is written next, and tried once more if that fails; if it still fails the write is not
 * failed, only logged, and the next read or [AgentDataStores.check] restores the primary from the mirror, recorded as
 * a recovery (which it is). A mirror that can't be written fails the write, before the primary is touched. So the
 * mirror is never behind a write that returned, and a damaged primary is restored ([AgentDataStores]) to the newest
 * write that did.
 *
 * Writes, and every restore, run one at a time under one lock. A reader that sees a primary damaged (short, or a
 * generation behind the mirror) confirms it under that lock before restoring, so a write in progress (mirror written,
 * primary not yet) is never taken for damage. A mirror exactly one generation ahead of an intact primary (a write
 * whose primary write failed, was cut off, or whose rename the storage lost) is the newer state.
 *
 * A primary that reads short is damaged, and so is one without a count once this agent has written the store
 * ([sealMark], a file kept apart from both, written and synced with its directory after the first counted write), or
 * whose mirror is counted (cut before its count). Uncounted, never sealed and with no counted mirror is an install
 * from before counts (0.5.0), kept as it is; with both files emptied after a seal, the store's own recovery rule
 * applies (for the kiosk store: fail closed).
 */
class MirroredDataStore internal constructor(
    private val primary: DataStore<Preferences>,
    private val mirror: DataStore<Preferences>,
    private val name: String,
    private val restore: suspend () -> Preferences,
    /** Null for a store opened bare (tests with faults). */
    private val sealMark: File? = null,
) : DataStore<Preferences> {
    private val writeLock = Mutex()

    /** Once true, stays true: the mark is never removed. */
    @Volatile private var sealed = false

    /** A damaged-looking value is confirmed, and restored, under the write lock ([intactLocked]). */
    override val data: Flow<Preferences> =
        primary.data.map { if (damaged(it)) writeLock.withLock { intactLocked() } else it }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        writeLock.withLock {
            val current = intactLocked()
            val changed = transform(current)
            // Unchanged (check() at every start): no new generation, only the mirror and the seal made sure of.
            val next = if (Integrity.sealed(changed, Integrity.generationOf(current)) == current) {
                current
            } else {
                Integrity.sealed(changed, Integrity.generationOf(current) + 1)
            }
            mirror.updateData { next }
            syncDirectory() // the commit point: from here the write has happened
            writePrimary(next)
            markSealed()
            next
        }

    /** The primary as it should be, restored when damaged; only under [writeLock] (a Mutex isn't re-entrant). */
    private suspend fun intactLocked(): Preferences {
        val current = primary.data.first()
        return if (damaged(current)) primary.updateData { if (damaged(it)) restore() else it } else current
    }

    /** After the mirror commit: tried twice; a failure is left to the next read, which restores from the mirror. */
    private suspend fun writePrimary(next: Preferences) {
        repeat(PRIMARY_TRIES) { attempt ->
            try {
                primary.updateData { next }
                return
            } catch (e: IOException) {
                warn("$name primary not written (try ${attempt + 1}); the mirror holds the write", e)
            }
        }
    }

    private suspend fun wasSealed(): Boolean =
        sealed || withContext(Dispatchers.IO) { sealMark?.exists() == true }.also { sealed = it }

    /**
     * After the first counted write: the mark, then its directory, synced, so a power loss after it can't undo it.
     * Off the caller's thread. Best effort: without it a store emptied with its mirror reads as never written.
     */
    private suspend fun markSealed() {
        val mark = sealMark
        if (sealed || mark == null) return
        withContext(Dispatchers.IO) {
            runCatching {
                if (!mark.exists()) FileOutputStream(mark).use { it.fd.sync() }
                sealed = true
            }.onFailure { warn("$name seal mark not written", it) }
        }
        syncDirectory()
    }

    /**
     * The store's directory (the mirror's and the mark's renames are durable only once it is synced, on Linux). Off the
     * caller's thread; best effort (the JVM unit tests have no android.system.Os).
     */
    private suspend fun syncDirectory() {
        val dir = sealMark?.parentFile ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
                try {
                    Os.fsync(fd)
                } finally {
                    Os.close(fd)
                }
            }.onFailure { warn("$name: directory not synced", it) }
        }
    }

    private suspend fun damaged(p: Preferences): Boolean = when {
        Integrity.isCounted(p) -> Integrity.isShort(p) || mirrorAhead(p)
        wasSealed() -> true
        else -> try {
            Integrity.isUsable(mirror.data.first())
        } catch (e: IOException) {
            warn("$name mirror unreadable; the primary is taken as it is", e)
            false
        }
    }

    /** The mirror holds the write after [p]'s, which the primary lost. */
    private suspend fun mirrorAhead(p: Preferences): Boolean = try {
        val m = mirror.data.first()
        Integrity.isUsable(m) && Integrity.generationOf(m) == Integrity.generationOf(p) + 1
    } catch (e: IOException) {
        warn("$name mirror unreadable; the primary is taken as it is", e)
        false
    }

    /** Copies the primary's current value to the mirror (a damaged primary is restored first, never copied). */
    suspend fun syncMirror() {
        try {
            writeLock.withLock {
                val now = intactLocked()
                mirror.updateData { now }
            }
            syncDirectory()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            warn("$name mirror not updated", e)
        }
    }

    private companion object {
        const val PRIMARY_TRIES = 2
    }
}

private const val TAG = "AgentDataStores"

/** Best effort: the JVM unit tests have no android.util.Log. */
private fun warn(message: String, e: Throwable? = null) {
    runCatching { Log.w(TAG, message, e) }
}
