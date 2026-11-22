package com.mdmesh.core.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mdmesh.core.kiosk.ExitJournal
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.EventType
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.TelemetryEventDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * #220 on real DataStore files: a store the storage corrupted is recovered on its next read (mirror, then the store's
 * own fallback, then empty), never failing reads with "Unable to parse preferences proto.".
 */
class AgentDataStoresTest {
    @get:Rule val tmp = TemporaryFolder()

    private val config = InMemoryConfigStateStore()
    private val events = RecordingEvents()
    private val jobs = mutableListOf<Job>()

    /** What lock task's allowlist says (null: empty, or an exit in progress). */
    private var lockedApps: List<String>? = null

    /** How many more times lock task's allowlist reads as unknown (a DPM call that throws). */
    private var lockedAppsUnknown = 0

    private val dir: File get() = tmp.root

    private fun file(name: String) = File(dir, "datastore/$name.preferences_pb")

    /** A fresh process: new DataStore instances over the same files. */
    private fun open(): AgentDataStores {
        val job = Job().also { jobs += it }
        return AgentDataStores(::file, config, events, CoroutineScope(Dispatchers.IO + job)) {
            if (lockedAppsUnknown-- > 0) throw IOException("lock task's allowlist couldn't be read") else lockedApps
        }
    }

    /** Ends a "process", so the next [open] reads the files again (DataStore allows one instance per file). */
    private fun close() = runBlocking { jobs.forEach { it.cancelAndJoin() }; jobs.clear() }

    @After fun tearDown() = close()

    private fun enrolled(): Map<Preferences.Key<*>, Any> = runBlocking {
        val stores = open()
        DeviceIdStore(stores.identity).saveCredentials("dev_1", "secret-1")
        stores.identity.data.first().asMap().also { close() }
    }

    private fun identityAfterReopen(): Pair<String?, String?> = runBlocking {
        val store = DeviceIdStore(open().identity)
        (store.current() to store.secret()).also { close() }
    }

    /** One file opened bare, for a fault: closed with the "process" ([close]). */
    private fun bare(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + Job().also { jobs += it })) {
            file(name)
        }

    /** The process dies as this file is about to be written. */
    private class DiesOnWrite(private val inner: DataStore<Preferences>) : DataStore<Preferences> by inner {
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("process died")
    }

    /** Its first [failures] writes fail (the storage, briefly), then it writes. */
    private class FailsWrites(private val inner: DataStore<Preferences>, private var failures: Int) :
        DataStore<Preferences> by inner {
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failures-- > 0) throw IOException("EIO")
            return inner.updateData(transform)
        }
    }

    /** A primary its count says is short: two entries counted, one there. */
    private fun shortPrimary(name: String) = runBlocking {
        bare(name).edit {
            it.clear()
            it[stringPreferencesKey("server_device_id")] = "dev_short"
            it[Integrity.ENTRIES] = 2
            it[Integrity.GENERATION] = 3
        }
        close()
    }

    @Test
    fun `an unreadable mirror fails the read, and nothing is decided, written or recorded`() {
        enrolled()
        shortPrimary("mdm_identity")
        val before = file("mdm_identity").readBytes()
        file("mdm_identity_mirror").delete()
        file("mdm_identity_mirror").mkdirs() // a read of it fails (EIO), it isn't absent
        val read = runBlocking { runCatching { open().identity.data.first() } }
        close()
        assertTrue("the read fails: ${read.exceptionOrNull()}", read.exceptionOrNull() is IOException)
        assertTrue("nothing persisted", before.contentEquals(file("mdm_identity").readBytes()))
        assertEquals(emptyList<String>(), events.recovered())
        assertEquals(null, config.revision())
    }

    @Test
    fun `an unreadable kiosk mirror with a configured kiosk restores that kiosk (fail closed), without one it waits`() {
        val kiosk = KioskApplyPayload(mode = "single", pinPackage = "com.example.pos")
        runBlocking { DataStoreKioskStateStore(open().kiosk).save(kiosk) }
        close()
        file("mdm_kiosk").writeBytes(ByteArray(32) { 0xFF.toByte() })
        file("mdm_kiosk_mirror").delete()
        file("mdm_kiosk_mirror").mkdirs()
        assertTrue("no evidence yet: the read waits",
            runBlocking { runCatching { DataStoreKioskStateStore(open().kiosk).load() } }.isFailure)
        close()
        assertEquals(emptyList<String>(), events.recovered())

        config.save(ConfigApplyPayload(revision = "r1", kiosk = kiosk))
        assertEquals(kiosk, runBlocking { DataStoreKioskStateStore(open().kiosk).load() })
        close()
        assertEquals(listOf("mdm_kiosk from configuration"), events.recovered())
    }

    @Test
    fun `check names a mirror that can't be written, for the console`() {
        enrolled()
        file("mdm_identity_mirror").delete()
        file("mdm_identity_mirror").mkdirs()
        val stores = open()
        assertEquals("dev_1", runBlocking { DeviceIdStore(stores.identity).current() }) // reads still work
        assertEquals(listOf("mirror unwritable: mdm_identity_mirror"), runBlocking { stores.check() })
        close()
    }

    @Test
    fun `an unreadable mirror fails the read of a corrupt primary too, and the next read recovers`() {
        enrolled()
        val mirror = file("mdm_identity_mirror").readBytes()
        file("mdm_identity").writeBytes(ByteArray(32) { 0xFF.toByte() })
        file("mdm_identity_mirror").delete()
        file("mdm_identity_mirror").mkdirs()
        val read = runBlocking { runCatching { open().identity.data.first() } }
        close()
        assertTrue(read.exceptionOrNull() is IOException)
        assertEquals(emptyList<String>(), events.recovered())
        file("mdm_identity_mirror").delete()
        file("mdm_identity_mirror").writeBytes(mirror) // the storage is back
        assertEquals("dev_1" to "secret-1", identityAfterReopen())
        assertEquals(listOf("mdm_identity from mirror"), events.recovered())
    }

    @Test
    fun `an unknown lock task allowlist decides nothing, and the next read decides on what is there`() {
        val adHoc = KioskApplyPayload(mode = "single", pinPackage = "com.example.pos")
        runBlocking { DataStoreKioskStateStore(open().kiosk).save(adHoc) }
        close()
        file("mdm_kiosk").writeBytes(ByteArray(0))
        file("mdm_kiosk_mirror").writeBytes(ByteArray(0))
        lockedApps = listOf("com.example.pos")
        lockedAppsUnknown = 1
        val stores = open()
        val first = runBlocking { runCatching { DataStoreKioskStateStore(stores.kiosk).load() } }
        assertTrue("unknown: the read fails", first.exceptionOrNull() is IOException)
        assertEquals(emptyList<String>(), events.recovered())
        assertEquals(AgentDataStores.lockedKiosk(listOf("com.example.pos")),
            runBlocking { DataStoreKioskStateStore(stores.kiosk).load() })
        close()
        assertEquals(listOf("mdm_kiosk from lock task"), events.recovered())
    }

    @Test
    fun `a never-kiosk device with an unknown allowlist ends with no kiosk once it is known, not a stranded one`() {
        runBlocking { DataStoreKioskStateStore(open().kiosk).save(null) }
        close()
        file("mdm_kiosk").writeBytes(ByteArray(0))
        file("mdm_kiosk_mirror").writeBytes(ByteArray(0))
        lockedApps = null // known: nothing allowed
        lockedAppsUnknown = 1
        val stores = open()
        assertTrue(runBlocking { runCatching { DataStoreKioskStateStore(stores.kiosk).load() } }.isFailure)
        assertNull(runBlocking { DataStoreKioskStateStore(stores.kiosk).load() })
        close()
        assertEquals(listOf("mdm_kiosk from empty"), events.recovered())
    }

    @Test
    fun `when the primary can't be written after a commit, an open collector sees it repaired`() = runBlocking {
        enrolled()
        val mirror = bare("mdm_identity_mirror")
        // Both tries of the write fail; the repair's write goes through.
        val store = MirroredDataStore(FailsWrites(bare("mdm_identity"), 2), mirror, "mdm_identity",
            restore = { mirror.data.first() })
        val seen = mutableListOf<String?>()
        val collector = launch(Dispatchers.Default) {
            store.data.collect { v -> synchronized(seen) { seen += v[newSecret] } }
        }
        // Open and holding the old value first, so only the repair tick can bring it the new one.
        withTimeout(10_000) { while (synchronized(seen) { seen.isEmpty() }) delay(10) }
        store.edit { it[newSecret] = "secret-2" }
        withTimeout(10_000) { while ("secret-2" !in synchronized(seen) { seen.toList() }) delay(10) }
        collector.cancelAndJoin()
        close()
        assertEquals("dev_1" to "secret-2", identityAfterReopen())
    }

    @Test
    fun `a repair whose write keeps failing records nothing until it lands`() = runBlocking {
        enrolled()
        shortPrimary("mdm_identity")
        val mirror = bare("mdm_identity_mirror")
        var settled = 0
        var restores = 0
        val primary = FailsWrites(bare("mdm_identity"), 3)
        val store = MirroredDataStore(primary, mirror, "mdm_identity",
            restore = { restores++; mirror.data.first() }, settled = { _ -> settled++ })
        repeat(3) { assertTrue(runCatching { store.data.first() }.isFailure) }
        assertEquals("not recorded while the repair fails", 0, settled)
        assertEquals("dev_1", store.data.first()[stringPreferencesKey("server_device_id")])
        assertTrue("recorded once it lands", settled > 0)
        close()
    }

    private val newSecret = stringPreferencesKey("device_secret")

    /** The identity store over files with faults; its primary is never damaged here. */
    private fun identity(primary: DataStore<Preferences>, mirror: DataStore<Preferences>) =
        MirroredDataStore(primary, mirror, "mdm_identity", restore = { error("the primary is not damaged") })

    @Test
    fun `a write whose primary fails after the mirror commit succeeds, and the next read restores it`() {
        enrolled()
        val cutOff = identity(DiesOnWrite(bare("mdm_identity")), bare("mdm_identity_mirror"))
        // The mirror is the commit point: the write happened, though its primary failed (twice).
        runBlocking { assertTrue(runCatching { cutOff.edit { it[newSecret] = "secret-2" } }.isSuccess) }
        close()

        assertEquals("the newest state written", "dev_1" to "secret-2", identityAfterReopen())
        assertEquals(listOf("mdm_identity from mirror"), events.recovered())
        file("mdm_identity").writeBytes(ByteArray(0))
        assertEquals("never an older state than written", "dev_1" to "secret-2", identityAfterReopen())
    }

    @Test
    fun `an intact primary is kept over a mirror more than one write ahead (not a lost write)`() {
        enrolled()
        val cutOff = identity(DiesOnWrite(bare("mdm_identity")), bare("mdm_identity_mirror"))
        runBlocking { runCatching { cutOff.edit { it[newSecret] = "secret-2" } } }
        val mirror = file("mdm_identity_mirror").readBytes()
        close()
        val twice = identity(bare("mdm_identity_mirror"), bare("unused"))
        runBlocking { twice.edit { it[newSecret] = "secret-3" } } // the mirror file, two generations ahead
        close()
        assertTrue(!mirror.contentEquals(file("mdm_identity_mirror").readBytes()))
        assertEquals("dev_1" to "secret-1", identityAfterReopen())
        assertEquals(emptyList<String>(), events.recovered())
    }

    /** [cancelCaller] runs the moment the mirror has committed (a worker stopped mid-write). */
    private class CancelsAfterCommit(
        private val inner: DataStore<Preferences>,
        private val cancelCaller: () -> Unit,
    ) : DataStore<Preferences> by inner {
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            inner.updateData(transform).also { cancelCaller() }
    }

    @Test
    fun `a write cancelled after its mirror commit still finishes, and the caller still sees the cancellation`() {
        enrolled()
        val primary = bare("mdm_identity")
        lateinit var write: Deferred<Preferences>
        val store = identity(primary, CancelsAfterCommit(bare("mdm_identity_mirror")) { write.cancel() })
        val result = runBlocking {
            write = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                store.edit { it[newSecret] = "secret-2" }
            }
            runCatching { write.await() }
        }
        assertTrue("cancelled: ${result.exceptionOrNull()}", result.exceptionOrNull() is CancellationException)
        val written = runBlocking { primary.data.first()[newSecret] }
        assertEquals("the primary holds the committed write", "secret-2", written)
        close()
        assertEquals(emptyList<String>(), events.recovered())
    }

    @Test
    fun `a mirror that can't be written fails the write before the primary is touched`() {
        enrolled()
        val noMirror = identity(bare("mdm_identity"), DiesOnWrite(bare("mdm_identity_mirror")))
        runBlocking { assertTrue(runCatching { noMirror.edit { it[newSecret] = "secret-2" } }.isFailure) }
        close()
        assertEquals("dev_1" to "secret-1", identityAfterReopen())
    }

    @Test
    fun `after a cut-off write check leaves the primary and its mirror the same`() {
        enrolled()
        val cutOff = identity(DiesOnWrite(bare("mdm_identity")), bare("mdm_identity_mirror"))
        runBlocking { runCatching { cutOff.edit { it[newSecret] = "secret-2" } } }
        close()
        runBlocking { open().check() }
        close()
        assertTrue(file("mdm_identity_mirror").readBytes().contentEquals(file("mdm_identity").readBytes()))
    }

    @Test
    fun `keys an older agent added after a downgrade are kept, not taken for damage`() {
        enrolled()
        // 0.5.0 adds a key and keeps the count as it was.
        runBlocking { bare("mdm_identity").edit { it[stringPreferencesKey("from_0_5_0")] = "x" } }
        close()
        val stores = open()
        assertEquals("x", runBlocking { stores.identity.data.first()[stringPreferencesKey("from_0_5_0")] })
        runBlocking { stores.check() } // counted again
        close()
        assertEquals(emptyList<String>(), events.recovered())
        file("mdm_identity").writeBytes(ByteArray(0))
        assertEquals("dev_1" to "secret-1", identityAfterReopen())
    }

    @Test
    fun `a reader during writes never takes a write in progress for damage`() = runBlocking {
        enrolled()
        val stores = open()
        val ids = DeviceIdStore(stores.identity)
        val reader = launch(Dispatchers.Default) {
            while (isActive) stores.identity.data.first()
        }
        repeat(100) { ids.saveCredentials("dev_1", "secret-$it") }
        reader.cancelAndJoin()
        close()
        assertEquals(emptyList<String>(), events.recovered())
        assertEquals("dev_1" to "secret-99", identityAfterReopen())
    }

    @Test
    fun `a fallback is sealed above a stale mirror and written to it, so the two match`() = runBlocking {
        val kiosk = KioskApplyPayload(mode = "single", pinPackage = "com.example.pos")
        config.save(ConfigApplyPayload(revision = "r1", kiosk = kiosk))
        // A mirror the storage cut short (its count says more entries than it holds), at generation 7.
        bare("mdm_kiosk_mirror").edit {
            it[stringPreferencesKey("kiosk_payload")] = "{}"
            it[Integrity.ENTRIES] = 5
            it[Integrity.GENERATION] = 7
        }
        close()
        file("mdm_kiosk").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(32) { 0xFF.toByte() }) }

        val stores = open()
        assertEquals(kiosk, DataStoreKioskStateStore(stores.kiosk).load())
        val restored = stores.kiosk.data.first()
        close()
        assertEquals(8L, Integrity.generationOf(restored))
        assertTrue(file("mdm_kiosk_mirror").readBytes().contentEquals(file("mdm_kiosk").readBytes()))
        assertEquals(listOf("mdm_kiosk from configuration"), events.recovered())
    }

    @Test
    fun `a write reaches the mirror`() = runBlocking {
        enrolled()
        assertTrue(file("mdm_identity_mirror").length() > 0)
        assertTrue(file("mdm_identity_mirror").readBytes().contentEquals(file("mdm_identity").readBytes()))
    }

    @Test
    fun `garbage primary is restored from the mirror, recorded, and the configuration marked stale`() {
        enrolled()
        config.save(ConfigApplyPayload(revision = "r1"))
        file("mdm_identity").writeBytes(ByteArray(64) { 0xFF.toByte() })
        assertEquals("dev_1" to "secret-1", identityAfterReopen())
        assertEquals(listOf("mdm_identity from mirror"), events.recovered())
        assertEquals(ConfigStateStore.STALE_REVISION, config.revision())
        // Rewritten on disk: the next process reads it cleanly, nothing more recovered.
        assertEquals("dev_1" to "secret-1", identityAfterReopen())
        assertEquals(1, events.recovered().size)
    }

    @Test
    fun `zero-filled primary (a lost block) is restored from the mirror`() {
        enrolled()
        file("mdm_identity").writeBytes(ByteArray(4096))
        assertEquals("dev_1" to "secret-1", identityAfterReopen())
        assertEquals(listOf("mdm_identity from mirror"), events.recovered())
    }

    @Test
    fun `a primary cut short at any offset, empty or between entries, is restored whole from the mirror`() {
        val original = enrolled()
        val whole = file("mdm_identity").readBytes()
        for (cut in whole.indices) {
            file("mdm_identity").writeBytes(whole.copyOf(cut))
            val read = runBlocking { open().identity.data.first().asMap() }
            close()
            assertEquals("cut at $cut", original, read)
        }
        assertEquals(List(whole.size) { "mdm_identity from mirror" }, events.recovered())
    }

    @Test
    fun `an empty primary file (a brownout) reads as the mirror, not as un-enrolled`() {
        enrolled()
        file("mdm_identity").writeBytes(ByteArray(0))
        assertEquals("dev_1" to "secret-1", identityAfterReopen())
        assertEquals(listOf("mdm_identity from mirror"), events.recovered())
        assertEquals(ConfigStateStore.STALE_REVISION, config.revision())
    }

    @Test
    fun `a write to a damaged primary builds on the restored contents`() = runBlocking {
        enrolled()
        file("mdm_identity").writeBytes(ByteArray(0))
        val stores = open()
        EnrollTokenStore(stores.enroll).save("tok") // another store: untouched
        DeviceIdStore(stores.identity).saveCredentials("dev_1", "secret-2")
        close()
        assertEquals("dev_1" to "secret-2", identityAfterReopen())
    }

    @Test
    fun `a corrupt mirror alone is copied again from the primary, quietly`() {
        enrolled()
        config.save(ConfigApplyPayload(revision = "r1"))
        file("mdm_identity_mirror").writeBytes(ByteArray(32) { 0xFF.toByte() })
        runBlocking { open().check() }
        close()
        assertEquals(emptyList<String>(), events.recovered())
        assertEquals("r1", config.revision())
        assertTrue(file("mdm_identity_mirror").readBytes().contentEquals(file("mdm_identity").readBytes()))
    }

    @Test
    fun `an install from before counts (0_5_0, no mirror) is read as it is, then sealed and mirrored`() = runBlocking {
        val job = Job()
        val legacy = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            file("mdm_identity")
        }
        legacy.edit { it[stringPreferencesKey("server_device_id")] = "dev_old" }
        job.cancelAndJoin()

        val stores = open()
        assertEquals("dev_old", DeviceIdStore(stores.identity).current())
        assertEquals(emptyList<String>(), stores.check())
        close()
        assertEquals(emptyList<String>(), events.recovered())
        file("mdm_identity").writeBytes(ByteArray(0))
        assertEquals("dev_old" to null, identityAfterReopen())
    }

    @Test
    fun `both files corrupt, identity starts empty and the loss is recorded once`() {
        enrolled()
        file("mdm_identity").writeBytes(ByteArray(16))
        file("mdm_identity_mirror").writeBytes(ByteArray(16) { 0xFF.toByte() })
        assertEquals(null to null, identityAfterReopen())
        assertEquals(listOf("mdm_identity from empty"), events.recovered())
    }

    @Test
    fun `kiosk with no usable mirror falls back to the configured kiosk (fail closed)`() = runBlocking {
        val kiosk = KioskApplyPayload(mode = "single", pinPackage = "com.example.pos")
        config.save(ConfigApplyPayload(revision = "r1", kiosk = kiosk))
        file("mdm_kiosk").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(32)) }
        assertEquals(kiosk, DataStoreKioskStateStore(open().kiosk).load())
        assertEquals(listOf("mdm_kiosk from configuration"), events.recovered())
        assertEquals(ConfigStateStore.STALE_REVISION, config.revision())
    }

    @Test
    fun `kiosk mirror wins over the configuration`() = runBlocking {
        val entered = KioskApplyPayload(mode = "single", pinPackage = "com.example.pos")
        DataStoreKioskStateStore(open().kiosk).save(entered)
        close()
        config.save(ConfigApplyPayload(revision = "r1", kiosk = entered.copy(pinPackage = "com.example.other")))
        file("mdm_kiosk").writeBytes(ByteArray(32) { 0x7F })
        assertEquals(entered, DataStoreKioskStateStore(open().kiosk).load())
        assertEquals(listOf("mdm_kiosk from mirror"), events.recovered())
    }

    @Test
    fun `a kiosk exit is kept, the empty mirror wins over the configured kiosk`() = runBlocking {
        val kiosk = KioskApplyPayload(mode = "single", pinPackage = "com.example.pos")
        val store = DataStoreKioskStateStore(open().kiosk)
        store.save(kiosk)
        store.save(null) // staff exited on the device
        close()
        config.save(ConfigApplyPayload(revision = "r1", kiosk = kiosk))
        file("mdm_kiosk").writeBytes(ByteArray(0))
        assertNull(DataStoreKioskStateStore(open().kiosk).load())
        assertEquals(listOf("mdm_kiosk from mirror"), events.recovered())
    }

    @Test
    fun `kiosk and mirror both emptied after the store was written fail closed into the configured kiosk`() =
        runBlocking {
            val kiosk = KioskApplyPayload(mode = "single", pinPackage = "com.example.pos")
            DataStoreKioskStateStore(open().kiosk).save(kiosk)
            close()
            config.save(ConfigApplyPayload(revision = "r1", kiosk = kiosk))
            file("mdm_kiosk").writeBytes(ByteArray(0))
            file("mdm_kiosk_mirror").writeBytes(ByteArray(0))
            assertEquals(kiosk, DataStoreKioskStateStore(open().kiosk).load())
            assertEquals(listOf("mdm_kiosk from configuration"), events.recovered())
        }

    @Test
    fun `identity and mirror both emptied after enrollment is a recorded loss, not a quiet fresh device`() {
        enrolled()
        file("mdm_identity").writeBytes(ByteArray(0))
        file("mdm_identity_mirror").delete()
        assertEquals(null to null, identityAfterReopen())
        assertEquals(listOf("mdm_identity from empty"), events.recovered())
    }

    @Test
    fun `an ad-hoc kiosk with both files lost stays locked to the apps lock task allows`() = runBlocking {
        val adHoc = KioskApplyPayload(mode = "single", pinPackage = "com.example.pos", password = "1234")
        DataStoreKioskStateStore(open().kiosk).save(adHoc) // kiosk.enter: not in any configuration
        close()
        config.save(ConfigApplyPayload(revision = "r1"))
        lockedApps = listOf("com.example.pos")
        file("mdm_kiosk").writeBytes(ByteArray(0))
        file("mdm_kiosk_mirror").writeBytes(ByteArray(0))

        val recovered = DataStoreKioskStateStore(open().kiosk).load()

        assertEquals(AgentDataStores.lockedKiosk(listOf("com.example.pos")), recovered)
        assertEquals("only the server ends it", "remote", recovered?.exitMode)
        assertEquals(listOf("mdm_kiosk from lock task"), events.recovered())
        assertEquals(ConfigStateStore.STALE_REVISION, config.revision())
    }

    @Test
    fun `with lock task released (an exit finished or in progress) a kiosk with both files lost is no kiosk`() =
        runBlocking {
            DataStoreKioskStateStore(open().kiosk).save(KioskApplyPayload(mode = "single", pinPackage = "a.b"))
            close()
            lockedApps = null
            file("mdm_kiosk").writeBytes(ByteArray(0))
            file("mdm_kiosk_mirror").writeBytes(ByteArray(0))
            assertNull(DataStoreKioskStateStore(open().kiosk).load())
            assertEquals(listOf("mdm_kiosk from empty"), events.recovered())
        }

    @Test
    fun `lock task counts as a kiosk unless an untried, authorised exit was under way`() {
        val own = "com.mdmesh.agent"
        val apps = listOf(own, "com.example.pos")
        fun exit(failed: Boolean = false, attempts: Int = 0) =
            ExitJournal.Pending(handBackHome = true, force = false, kiosk = "k", failed = failed, attempts = attempts)
        assertEquals(listOf("com.example.pos"), AgentDataStores.lockedAppsOf(apps, own) { null })
        assertNull("only the agent itself", AgentDataStores.lockedAppsOf(listOf(own), own) { null })
        assertNull("an exit on its way out", AgentDataStores.lockedAppsOf(apps, own) { exit() })
        assertEquals("a failed relock stays in kiosk", listOf("com.example.pos"),
            AgentDataStores.lockedAppsOf(apps, own) { exit(failed = true) })
        assertEquals("an exit already tried settles in kiosk", listOf("com.example.pos"),
            AgentDataStores.lockedAppsOf(apps, own) { exit(attempts = 1) })
        assertEquals("a journal that can't be read is no exit", listOf("com.example.pos"),
            AgentDataStores.lockedAppsOf(apps, own) { throw IllegalStateException("unreadable") })
        assertTrue("an allowlist that can't be read is unknown, not a decision",
            runCatching { AgentDataStores.lockedAppsOf(null, own) { null } }.exceptionOrNull() is IOException)
    }

    @Test
    fun `kiosk with nothing to recover from is no kiosk`() = runBlocking {
        file("mdm_kiosk").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(32)) }
        assertNull(DataStoreKioskStateStore(open().kiosk).load())
        assertEquals(listOf("mdm_kiosk from empty"), events.recovered())
    }

    @Test
    fun `check seeds the mirror of an install from before mirrors, which then restores the primary`() = runBlocking {
        // What an older agent left: the primary alone.
        val job = Job()
        val legacy = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            file("mdm_enroll")
        }
        legacy.edit { it[stringPreferencesKey("enroll_token")] = "tok-1" }
        job.cancelAndJoin()
        assertFalse(file("mdm_enroll_mirror").exists())

        assertEquals(emptyList<String>(), open().check())
        close()
        assertTrue(file("mdm_enroll_mirror").exists())

        file("mdm_enroll").writeBytes(ByteArray(8) { 0xFF.toByte() })
        assertEquals("tok-1", EnrollTokenStore(open().enroll).token())
        assertEquals(listOf("mdm_enroll from mirror"), events.recovered())
    }

    @Test
    fun `a clear reaches the mirror too, so a later recovery does not bring old credentials back`() {
        enrolled()
        runBlocking { DeviceIdStore(open().identity).clear() }
        close()
        file("mdm_identity").writeBytes(ByteArray(16))
        assertEquals(null to null, identityAfterReopen())
    }

    private class RecordingEvents : EventSink {
        val all = mutableListOf<Pair<String, String?>>()
        override fun record(type: String, detail: String?) { synchronized(all) { all += type to detail } }
        override fun drain(max: Int): List<TelemetryEventDto> = emptyList()
        override fun restore(events: List<TelemetryEventDto>) = Unit
        fun recovered() = synchronized(all) { all.filter { it.first == EventType.STORAGE_RECOVERED }.map { it.second } }
    }
}
