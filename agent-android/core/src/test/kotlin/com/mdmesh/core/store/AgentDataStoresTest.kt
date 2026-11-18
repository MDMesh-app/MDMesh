@file:Suppress("ForbiddenImport") // Writes a file as an agent before mirrors did.

package com.mdmesh.core.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.EventType
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.TelemetryEventDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #220 on real DataStore files: a store the storage corrupted is recovered on its next read (mirror, then the store's
 * own fallback, then empty), never failing reads with "Unable to parse preferences proto.".
 */
class AgentDataStoresTest {
    @get:Rule val tmp = TemporaryFolder()

    private val config = InMemoryConfigStateStore()
    private val events = RecordingEvents()
    private val jobs = mutableListOf<Job>()

    private val dir: File get() = tmp.root

    private fun file(name: String) = File(dir, "datastore/$name.preferences_pb")

    /** A fresh process: new DataStore instances over the same files. */
    private fun open(): AgentDataStores {
        val job = Job().also { jobs += it }
        return AgentDataStores(::file, config, events, CoroutineScope(Dispatchers.IO + job))
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
    fun `a primary truncated at any offset never fails a read and never yields a value it did not hold`() {
        val original = enrolled()
        val whole = file("mdm_identity").readBytes()
        for (cut in whole.indices) {
            file("mdm_identity").writeBytes(whole.copyOf(cut))
            val read = runBlocking { open().identity.data.first().asMap() }
            close()
            // Restored from the mirror, or (cut at an entry boundary, which parses) a subset of what was there.
            assertTrue("cut at $cut read $read", read == original || original.entries.containsAll(read.entries))
            file("mdm_identity_mirror").writeBytes(whole) // a recovery rewrites the primary only; keep the mirror
        }
    }

    @Test
    fun `both files corrupt, identity starts empty and both recoveries are recorded`() {
        enrolled()
        file("mdm_identity").writeBytes(ByteArray(16))
        file("mdm_identity_mirror").writeBytes(ByteArray(16) { 0xFF.toByte() })
        assertEquals(null to null, identityAfterReopen())
        assertEquals(listOf("mdm_identity_mirror from empty", "mdm_identity from empty"), events.recovered())
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
        override fun drain(): List<TelemetryEventDto> = emptyList()
        override fun restore(events: List<TelemetryEventDto>) = Unit
        fun recovered() = synchronized(all) { all.filter { it.first == EventType.STORAGE_RECOVERED }.map { it.second } }
    }
}
