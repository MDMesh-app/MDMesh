package com.mdmesh.agent.stress

import android.app.ActivityManager
import com.mdmesh.agent.KioskLauncherActivity
import com.mdmesh.core.store.AgentDataStores
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.store.SharedPrefsConfigStateStore
import com.mdmesh.proto.ConfigApplyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.Robolectric

/**
 * #220 on the kiosk HOME: a kiosk store the storage corrupted used to fail every read ("Unable to parse preferences
 * proto."), so the launcher's collector crashed HOME on every start. It is recovered on its first read (mirror, then
 * the configured kiosk) and the kiosk carries on, through the lifecycle chaos the other stress tests use.
 */
class KioskStoreCorruptionTest : KioskHarness() {

    private val storage by lazy { StoreCorruption(app) }

    /** HOME starts over whatever is on disk (nothing saved through the store first). */
    private fun startHome() {
        ctl = Robolectric.buildActivity(KioskLauncherActivity::class.java).setup()
        settle()
    }

    @Test fun corruptKioskStoreIsRestoredFromItsMirrorAndTheKioskStays() {
        storage.corruptKiosk(mirror = single)
        startHome()
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
        assertEquals(1, pinnedStarts())
        assertEquals(single, storedKiosk())
        assertEquals(listOf("mdm_kiosk from mirror"), storage.recoveries())
    }

    @Test fun corruptKioskStoreWithoutMirrorFailsClosedIntoTheConfiguredKiosk() {
        val config = SharedPrefsConfigStateStore(app)
        config.save(ConfigApplyPayload(revision = "a".repeat(64), kiosk = grid))
        storage.corruptKiosk(mirror = null)
        startHome()
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
        assertEquals(grid, storedKiosk())
        assertEquals(listOf("mdm_kiosk from configuration"), storage.recoveries())
        assertEquals("the server re-sends the configuration", ConfigStateStore.STALE_REVISION, config.revision())
    }

    @Test fun corruptKioskStoreWithNothingToRecoverIsIdleNotACrash() {
        storage.corruptKiosk(mirror = null)
        startHome()
        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE, lockTaskState())
        assertNull(storedKiosk())
    }

    @Test fun recoveredKioskSurvivesReturnsRecreationAndProcessDeath() {
        storage.corruptKiosk(mirror = single)
        startHome()
        drainStarts()
        coverAndReturn()
        assertEquals("one open per return", 1, pinnedStarts())
        ctl!!.recreate()
        settle()
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
        // Process death: a new HOME reads the file the recovery rewrote, with nothing left to recover.
        ctl!!.pause().stop().destroy()
        startHome()
        assertEquals(single, storedKiosk())
        assertEquals(listOf("mdm_kiosk from mirror"), storage.recoveries())
    }

    @Test fun unreadableKioskStoreKeepsHomeUpAndOtherwiseIdle() {
        storage.unreadable(AgentDataStores.KIOSK)
        startHome()
        // No crash: the collector retries in the background; nothing is applied from a store it can't read.
        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE, lockTaskState())
        ctl!!.pause().stop().start().resume()
        settle()
    }
}
