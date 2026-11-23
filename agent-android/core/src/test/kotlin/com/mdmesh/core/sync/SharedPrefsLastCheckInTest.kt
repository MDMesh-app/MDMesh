package com.mdmesh.core.sync

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.concurrent.thread

/** The persisted store on the real SharedPreferences, as the status screen follows it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPrefsLastCheckInTest {

    private val store by lazy { SharedPrefsLastCheckIn(ApplicationProvider.getApplicationContext()) }

    @Test
    fun `a success recorded on an IO thread reaches a follower on the main thread`() = runTest {
        // Check-ins run on IO, and SharedPreferences then posts the change to the main looper instead of calling the
        // listener inline.
        val seen = mutableListOf<Long?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.okAtMsChanges.collect { seen += it } }
        assertEquals(listOf<Long?>(null), seen) // never yet

        thread { store.okAtMs = 1_000L }.join()
        assertEquals(1_000L, store.okAtMs)
        assertEquals("delivered on the main looper, not inline", listOf<Long?>(null), seen)

        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(null, 1_000L), seen)

        thread { store.okAtMs = 2_000L }.join()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(null, 1_000L, 2_000L), seen)
    }

    @Test
    fun `a new follower starts from the persisted value`() = runTest {
        thread { store.okAtMs = 5_000L }.join()
        shadowOf(Looper.getMainLooper()).idle()

        val seen = mutableListOf<Long?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            SharedPrefsLastCheckIn(ApplicationProvider.getApplicationContext()).okAtMsChanges.collect { seen += it }
        }
        assertEquals(listOf<Long?>(5_000L), seen)
    }
}
