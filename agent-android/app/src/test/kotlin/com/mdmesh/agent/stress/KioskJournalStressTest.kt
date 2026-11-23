package com.mdmesh.agent.stress

import android.app.ActivityManager
import android.provider.Settings
import com.mdmesh.core.kiosk.ExitJournal
import com.mdmesh.core.store.SharedPrefsConfigStateStore
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.KioskApplyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kiosk HOME at start with an exit a process death cut off, and a crash-loop hold across boots (review 3, R1-R3).
 */
class KioskJournalStressTest : KioskHarness() {

    private val journal get() = app.getSharedPreferences("mdm_kiosk_exit", 0)

    /** An exit of [k] that began (entry written) and was cut off before its last step. */
    private fun cutOffExitOf(k: KioskApplyPayload) {
        journal.edit().putBoolean("pending", true).putBoolean("hand_back_home", true).putBoolean("force", true)
            .putString("kiosk", ExitJournal.fingerprint(k)).commit()
    }

    private fun configure(k: KioskApplyPayload?) {
        val store = SharedPrefsConfigStateStore(app)
        if (k == null) store.clear() else store.save(ConfigApplyPayload(revision = "r".repeat(64), kiosk = k))
    }

    /** R1: the configuration still has this kiosk: the launcher relocks it rather than finishing the exit. */
    @Test fun cutOffExitOnConfiguredKioskRelocksAtLauncherStart() {
        configure(grid)
        cutOffExitOf(grid)
        launch(grid)
        assertEquals(grid, storedKiosk())
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
        assertFalse(activity.isFinishing)
        assertFalse("journal cleared", journal.getBoolean("pending", false))
    }

    /** R2: a kiosk from kiosk.enter (no configured kiosk): the authorized exit is finished. */
    @Test fun cutOffExitOnCommandKioskIsFinishedAtLauncherStart() {
        configure(null)
        cutOffExitOf(grid)
        launch(grid)
        assertNull(storedKiosk())
        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE, lockTaskState())
        assertFalse(journal.getBoolean("pending", false))
    }

    /** R3: a hold belongs to its boot; a hold in the current boot survives a recreation. */
    @Test fun holdFromAnEarlierBootIsNotInherited() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)
        launch(single)
        bounce(4)
        assertTrue(holdShowing())
        ageCrashFaults(61_000)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 8)
        drainStarts()
        processDeathRestore()
        assertFalse("hold from boot 7 not inherited", holdShowing())
        assertEquals(1, pinnedStarts())
        bounce(4)
        assertTrue(holdShowing())
        recreate()
        assertTrue("held again in boot 8", holdShowing())
    }
}
