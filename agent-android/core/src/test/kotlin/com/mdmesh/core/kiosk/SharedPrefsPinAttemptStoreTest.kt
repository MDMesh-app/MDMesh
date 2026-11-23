package com.mdmesh.core.kiosk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mdmesh.kiosk.ExitPinGate
import com.mdmesh.kiosk.PinAttempts
import com.mdmesh.kiosk.SharedPrefsPinAttemptStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The exit PIN count on disk: every field round-trips, and a file from an agent before 0.5.0 keeps its lockout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPrefsPinAttemptStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(ExitPinGate.PREFERENCE_NAME, Context.MODE_PRIVATE)

    @Test
    fun `a fresh install reads as no wrong PINs`() {
        assertEquals(0, SharedPrefsPinAttemptStore(context).read().failures)
    }

    @Test
    fun `every field survives a new store (process death, an update)`() {
        val saved = PinAttempts(failures = 8, lastFailureAt = 123_456L, pinKey = ExitPinGate.keyOf("482139"))
        assertTrue(SharedPrefsPinAttemptStore(context).write(saved))
        assertEquals(saved, SharedPrefsPinAttemptStore(context).read())
    }

    @Test
    fun `a cleared count drops the PIN fingerprint`() {
        val store = SharedPrefsPinAttemptStore(context)
        store.write(PinAttempts(failures = 2, lastFailureAt = 1L, pinKey = "k"))
        store.write(PinAttempts())
        assertEquals(PinAttempts(), store.read())
    }

    @Test
    fun `a file from an agent before 0dot5 keeps its lockout through the update`() {
        // What 0.4.x wrote: only the count and the clock reading. 4 wrong PINs = its 1 min step.
        prefs.edit().putInt("failures", 4).putLong("last_failure_at", 50_000L).commit()
        val read = SharedPrefsPinAttemptStore(context).read()
        assertEquals(PinAttempts(failures = 4, lastFailureAt = 50_000L, pinKey = null, schema = 1), read)
        val gate = ExitPinGate(SharedPrefsPinAttemptStore(context)) { 60_000L }
        assertEquals("10 s of its minute were used", 50_000L, gate.remainingLockMs("1234"))
    }
}
