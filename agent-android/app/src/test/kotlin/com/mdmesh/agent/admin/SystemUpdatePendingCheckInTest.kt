package com.mdmesh.agent.admin

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mdmesh.core.sync.CheckInWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An OS update that becomes pending (or stops being pending) while a check-in is already in flight must still reach
 * the console promptly: the callback queues a follow-up check-in after the current one, rather than being dropped
 * into it (that one may already have read its telemetry).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemUpdatePendingCheckInTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun checkIns(): List<WorkInfo> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork("mdm-checkin-now").get()

    @Test fun aPendingUpdateDuringACheckInQueuesAFollowUp() {
        CheckInWorker.scheduleNow(app) // the check-in already in flight
        val before = checkIns()
        assertTrue("a check-in is in flight: ${before.map { it.state }}", before.any { !it.state.isFinished })

        AdminReceiver().onSystemUpdatePending(app, Intent(), System.currentTimeMillis())

        val after = checkIns()
        assertEquals("a follow-up check-in is queued: ${after.map { it.state }}", before.size + 1, after.size)
        assertTrue(after.none { it.state == WorkInfo.State.CANCELLED })
    }
}
