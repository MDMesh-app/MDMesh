package com.mdmesh.core.sync

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CheckInWorkerTest {

    @Test
    fun `the process-start check-in replaces a stale one and leaves the enroll chain alone`() {
        // A one-shot from a dead process can sit in a long retry backoff (an outage); KEEP would leave the new process
        // waiting on it. REPLACE is safe here: at process start no check-in of this name can be running in this
        // process yet, and a separate name never cancels the "now" chain, which may carry an enroll.
        val (name, policy) = CheckInWorker.PROCESS_START_WORK
        assertEquals(ExistingWorkPolicy.REPLACE, policy)
        assertNotEquals("mdm-checkin-now", name)
        assertNotEquals("mdm-checkin", name)
    }
}
