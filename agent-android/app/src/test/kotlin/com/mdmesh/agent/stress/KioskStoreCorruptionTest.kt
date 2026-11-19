package com.mdmesh.agent.stress

import android.app.ActivityManager
import android.os.Looper
import androidx.lifecycle.Lifecycle
import com.mdmesh.agent.KioskLauncherActivity
import com.mdmesh.core.store.AgentDataStores
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.store.SharedPrefsConfigStateStore
import com.mdmesh.proto.ConfigApplyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * #220 on the kiosk HOME: a kiosk store the storage corrupted used to fail every read ("Unable to parse preferences
 * proto."), so the launcher's collector crashed HOME on every start. It is recovered on its first read (mirror, then
 * the configured kiosk) and the kiosk carries on, through the lifecycle chaos the other stress tests use. The stores
 * deliver on real threads, so each test waits for what it expects ([StoreCorruption.eventually]).
 */
class KioskStoreCorruptionTest : KioskHarness() {

    private val storage by lazy { StoreCorruption(app) }

    /** HOME starts over whatever is on disk (nothing saved through the store first). */
    private fun startHome() {
        ctl = Robolectric.buildActivity(KioskLauncherActivity::class.java).setup()
    }

    private fun awaitLocked() =
        storage.eventually("lock task") { lockTaskState() == ActivityManager.LOCK_TASK_MODE_LOCKED }

    /** Waits for the pinned app to open, then counts every open of it until things settle. */
    private fun pinnedOpens(): Int {
        var opens = 0
        storage.eventually("the pinned app") { opens += pinnedStarts(); opens > 0 }
        settle()
        return opens + pinnedStarts()
    }

    private fun awaitRecoveries(vararg expected: String) =
        storage.eventually("recoveries ${expected.toList()}") { storage.recoveries() == expected.toList() }

    @Test fun corruptKioskStoreIsRestoredFromItsMirrorAndTheKioskStays() {
        storage.corruptKiosk(mirror = single)
        startHome()
        awaitLocked()
        assertEquals(1, pinnedOpens())
        assertEquals(single, storedKiosk())
        awaitRecoveries("mdm_kiosk from mirror")
    }

    @Test fun corruptKioskStoreWithoutMirrorFailsClosedIntoTheConfiguredKiosk() {
        val config = SharedPrefsConfigStateStore(app)
        config.save(ConfigApplyPayload(revision = "a".repeat(64), kiosk = grid))
        storage.corruptKiosk(mirror = null)
        startHome()
        awaitLocked()
        assertEquals(grid, storedKiosk())
        awaitRecoveries("mdm_kiosk from configuration")
        assertEquals("the server re-sends the configuration", ConfigStateStore.STALE_REVISION, config.revision())
    }

    @Test fun corruptKioskStoreWithNothingToRecoverIsIdleNotACrash() {
        storage.corruptKiosk(mirror = null)
        startHome()
        // Recorded only once a read delivered the restored value (the launcher's collector is the only reader here):
        // the idle below is the delivered "no kiosk", not a launcher that never heard from the store.
        awaitRecoveries("mdm_kiosk from empty")
        settle()
        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE, lockTaskState())
        assertNull(storedKiosk())
    }

    @Test fun recoveredKioskSurvivesReturnsRecreationAndProcessDeath() {
        storage.corruptKiosk(mirror = single)
        startHome()
        awaitLocked()
        assertEquals(1, pinnedOpens())
        coverAndReturn()
        assertEquals("one open per return", 1, pinnedStarts())
        ctl!!.recreate()
        awaitLocked()
        // Process death: a new HOME reads the file the recovery rewrote, with nothing left to recover.
        ctl!!.pause().stop().destroy()
        startHome()
        awaitLocked()
        assertEquals(single, storedKiosk())
        awaitRecoveries("mdm_kiosk from mirror")
    }

    @Test fun unreadableKioskStoreKeepsHomeUpAndPicksTheKioskUpOnceReadable() {
        storage.unreadable(AgentDataStores.KIOSK)
        startHome()
        settle()
        ctl!!.pause().stop().start().resume()
        settle()
        // Nothing is applied from a store that can't be read.
        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE, lockTaskState())
        // The storage comes back: the collector's backoff retries (on the main looper's clock) pick the kiosk up, so
        // HOME went through at least one retry without crashing.
        storage.readableAgain(single)
        storage.eventually("the kiosk, after a retry") {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            lockTaskState() == ActivityManager.LOCK_TASK_MODE_LOCKED
        }
        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
        assertFalse(activity.isFinishing)
    }
}
