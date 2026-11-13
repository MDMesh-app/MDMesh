package com.mdmesh.core.sync

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CheckInWorkerTest {

    /** WorkManager's unique-work rules: KEEP leaves a pending work alone, REPLACE swaps in a new one. */
    private class FakeQueue : CheckInWorker.Companion.CheckInQueue {
        val pending = linkedMapOf<String, Int>()
        private var nextId = 100
        override fun enqueue(name: String, policy: ExistingWorkPolicy) {
            if (policy == ExistingWorkPolicy.KEEP && name in pending) return
            pending[name] = ++nextId
        }
        override fun cancel(name: String) {
            pending.remove(name)
        }
    }

    @Test
    fun `a boot makes one start-up check-in through the worker, as before`() {
        // Application.onCreate, then BootReceiver (or a self-update, or provisioning) ask for one each: they must
        // collapse into one work, as they did when both used scheduleNow.
        val queue = FakeQueue()
        CheckInWorker.scheduleOnProcessStart(queue)
        CheckInWorker.scheduleNow(queue)
        assertEquals(listOf("mdm-checkin-now"), queue.pending.keys.toList())
    }

    @Test
    fun `the start-up check-in replaces one a dead process left waiting in backoff`() {
        val queue = FakeQueue().apply { pending["mdm-checkin-now"] = 1 } // hours into its retry backoff
        CheckInWorker.scheduleOnProcessStart(queue)
        assertNotEquals("replaced, not kept", 1, queue.pending["mdm-checkin-now"])
    }

    @Test
    fun `a start-up work left by a pre-release build is cancelled`() {
        val queue = FakeQueue().apply { pending["mdm-checkin-start"] = 1 }
        CheckInWorker.scheduleOnProcessStart(queue)
        assertFalse("mdm-checkin-start" in queue.pending)
    }

    @Test
    fun `a later scheduleNow never replaces the pending one`() {
        // It may be mid-enroll: replacing it could burn the single-use token.
        val queue = FakeQueue().apply { pending["mdm-checkin-now"] = 1 }
        CheckInWorker.scheduleNow(queue)
        assertEquals(1, queue.pending["mdm-checkin-now"])
    }
}
