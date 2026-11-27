package com.mdmesh.agent.stress

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.mdmesh.agent.admin.AdminReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowActivity

/**
 * The kiosk HOME arms the kiosk guard at start, before it ever starts lock task: a kiosk saved with no guard (an
 * agent updated in kiosk from a version without one, a record lost) is never locked unguarded.
 */
@Config(shadows = [KioskGuardBeforeLockTest.RecordsLockTask::class])
class KioskGuardBeforeLockTest : KioskHarness() {

    /** Records the device's permission policy and app-control restriction at each startLockTask. */
    @Implements(Activity::class)
    class RecordsLockTask : ShadowActivity() {
        @Implementation
        override fun startLockTask() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val dpm = app.getSystemService(DevicePolicyManager::class.java)
            val users = app.getSystemService(UserManager::class.java)
            atLock += dpm.getPermissionPolicy(ComponentName(app, AdminReceiver::class.java)) to
                users.hasUserRestriction(UserManager.DISALLOW_APPS_CONTROL)
            super.startLockTask()
        }

        companion object {
            val atLock = mutableListOf<Pair<Int, Boolean>>()
        }
    }

    @Before fun clear() = RecordsLockTask.atLock.clear()

    @Test fun guardIsArmedAtTheFirstStartLockTask() {
        launch(grid) // saved as by an older agent: no guard record, no policy set
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
        assertTrue("lock task was started", RecordsLockTask.atLock.isNotEmpty())
        assertEquals(DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT to true, RecordsLockTask.atLock.first())
    }
}
