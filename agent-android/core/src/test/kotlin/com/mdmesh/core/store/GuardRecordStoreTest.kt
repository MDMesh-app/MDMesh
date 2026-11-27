package com.mdmesh.core.store

import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_PROMPT
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.mdmesh.core.kiosk.DataStoreGuardRecordStore
import com.mdmesh.core.kiosk.GuardRecord
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

/** The kiosk guard's record on the agent's mirrored store: every field round-trips, and damage is recovered. */
class GuardRecordStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val jobs = mutableListOf<Job>()
    private val recovered = mutableListOf<String?>()
    /** Records recoveries; a proxy, so it fits every EventSink shape the agent has had (this test is backported). */
    private val noEvents = Proxy.newProxyInstance(EventSink::class.java.classLoader, arrayOf(EventSink::class.java)) {
            _, method, args ->
        when (method.name) {
            "record" -> {
                val detail = args?.getOrNull(1) as String?
                if (args?.get(0) == EventType.STORAGE_RECOVERED) synchronized(recovered) { recovered += detail }
                Unit
            }
            "drain" -> emptyList<Any>()
            else -> Unit
        }
    } as EventSink

    private fun file(name: String) = File(tmp.root, "datastore/$name.preferences_pb")

    /** A fresh process: new DataStore instances over the same files. */
    private fun open(): AgentDataStores {
        val job = Job().also { jobs += it }
        return AgentDataStores(::file, InMemoryConfigStateStore(), noEvents, CoroutineScope(Dispatchers.IO + job))
    }

    private fun close() = runBlocking { jobs.forEach { it.cancelAndJoin() }; jobs.clear() }

    @After fun tearDown() = close()

    private val full = GuardRecord(
        priorPolicy = PERMISSION_POLICY_PROMPT,
        packages = setOf("com.a", "com.b"),
        fixedBefore = setOf("com.c|android.permission.CAMERA"),
        denied = setOf("com.a|android.permission.ACCESS_BACKGROUND_LOCATION"),
        factoryResetBefore = false,
        releasing = true,
    )

    @Test fun `no record reads as none`() {
        assertNull(DataStoreGuardRecordStore(open().guard).load())
    }

    @Test fun `every field survives a new process`() {
        DataStoreGuardRecordStore(open().guard).save(full)
        close()
        assertEquals(full, DataStoreGuardRecordStore(open().guard).load())
    }

    @Test fun `a snapshot not taken stays apart from an empty one`() {
        val store = DataStoreGuardRecordStore(open().guard)
        store.save(full.copy(fixedBefore = null))
        assertNull(store.load()?.fixedBefore)
        store.save(full.copy(fixedBefore = emptySet()))
        assertEquals(emptySet<String>(), store.load()?.fixedBefore)
    }

    @Test fun `a cleared record reads as none`() {
        val store = DataStoreGuardRecordStore(open().guard)
        store.save(full)
        store.clear()
        assertNull(store.load())
    }

    @Test fun `fields missing from a record read as the cautious values`() {
        val stores = open()
        runBlocking { stores.guard.edit { it[intPreferencesKey("prior_policy")] = PERMISSION_POLICY_PROMPT } }
        val r = DataStoreGuardRecordStore(stores.guard).load()
        assertEquals(PERMISSION_POLICY_PROMPT, r?.priorPolicy)
        assertNull("no snapshot: no grant released", r?.fixedBefore)
        assertEquals("factory reset taken as blocked before", true, r?.factoryResetBefore)
        assertEquals(emptySet<String>(), r?.packages)
    }

    @Test fun `a damaged record is recovered at start, before anything reads it`() {
        val stores = open()
        runBlocking { stores.check() }
        DataStoreGuardRecordStore(stores.guard).save(full)
        close()
        file(AgentDataStores.GUARD).writeBytes(ByteArray(32) { 0xFF.toByte() })
        runBlocking { open().check() }
        val events = synchronized(recovered) { recovered.toList() }
        assertEquals(listOf<String?>("${AgentDataStores.GUARD} from mirror"), events)
    }

    @Test fun `a damaged record file is restored from its mirror`() {
        val stores = open()
        runBlocking { stores.check() }
        DataStoreGuardRecordStore(stores.guard).save(full.copy(priorPolicy = PERMISSION_POLICY_AUTO_GRANT))
        close()
        file(AgentDataStores.GUARD).writeBytes(ByteArray(32) { 0xFF.toByte() })
        val restored = DataStoreGuardRecordStore(open().guard).load()
        assertEquals(full.copy(priorPolicy = PERMISSION_POLICY_AUTO_GRANT), restored)
    }
}
