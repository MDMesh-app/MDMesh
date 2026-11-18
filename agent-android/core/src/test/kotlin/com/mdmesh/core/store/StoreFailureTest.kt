package com.mdmesh.core.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** A storage error (not corruption, which is recovered): a named error for reads, a retry for flows (#220). */
class StoreFailureTest {

    /** Reads fail with EIO the first [failures] times, then hold [value]. */
    private class FailingStore(private var failures: Int, private val value: Preferences) : DataStore<Preferences> {
        var reads = 0
        override val data: Flow<Preferences> = flow {
            reads++
            if (failures-- > 0) throw IOException("EIO")
            emit(value)
        }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("ENOSPC")
    }

    private val kiosk = KioskApplyPayload(mode = "single", pinPackage = "com.example.pos")

    @Test
    fun `kiosk load names the store and the operation`() = runTest {
        try {
            DataStoreKioskStateStore(FailingStore(1, emptyPreferences())).load()
            fail("expected a storage error")
        } catch (e: StoreUnavailableException) {
            assertEquals("kiosk state could not be read: EIO", e.message)
        }
    }

    @Test
    fun `kiosk save names the store and the operation`() = runTest {
        try {
            DataStoreKioskStateStore(FailingStore(0, emptyPreferences())).save(kiosk)
            fail("expected a storage error")
        } catch (e: StoreUnavailableException) {
            assertEquals("kiosk state could not be saved: ENOSPC", e.message)
        }
    }

    @Test
    fun `kiosk flow retries with backoff and never emits no-kiosk for an error`() = runTest {
        val store = FailingStore(3, DataStoreKioskStateStore.prefsOf(kiosk))
        assertEquals(listOf(kiosk), DataStoreKioskStateStore(store).flow().take(1).toList())
        assertEquals(4, store.reads)
        assertTrue("backed off 1 + 2 + 4 s", testScheduler.currentTime >= 7_000)
    }

    @Test
    fun `device id flow retries rather than failing its collector`() = runTest {
        val id = DeviceIdStore(FailingStore(2, emptyPreferences())).deviceId.first()
        assertEquals(null, id)
    }

    @Test
    fun `credentials mark reads as none while failing, then the stored value`() = runTest {
        val marks = EnrollTokenStore(FailingStore(1, emptyPreferences())).credentialsLostMarks.take(1).toList()
        assertEquals(listOf<Long?>(null), marks)
    }

    @Test
    fun `a read that works starts the backoff over`() = runTest {
        var collections = 0
        val values = flow {
            collections++
            if (collections == 2) emit(1)
            if (collections < 3) throw IOException("EIO")
            emit(2)
        }
        assertEquals(listOf(1, 2), values.retryOnStorageError("test").take(2).toList())
        assertEquals("1 s, a value, then 1 s again (not 2 s)", 2_000L, testScheduler.currentTime)
    }

    @Test
    fun `backoff doubles up to a minute`() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 32_000L, 60_000L, 60_000L),
            listOf(0L, 1, 2, 5, 6, 40).map(::storageRetryDelay),
        )
    }
}
