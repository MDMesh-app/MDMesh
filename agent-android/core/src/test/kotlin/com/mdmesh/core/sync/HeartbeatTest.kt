package com.mdmesh.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HeartbeatTest {

    private val scope = TestScope()
    private var finishedAt: Long? = null
    private var finishes = 0
    private var late = false
    private var error: Throwable? = null

    private fun beat(work: suspend () -> Unit) = Heartbeat.launch(
        scope = scope,
        onError = { error = it },
        onLate = { late = true },
        finish = { finishes++; finishedAt = scope.currentTime },
        work = work,
    )

    @Test
    fun `a quick heartbeat releases its broadcast when done`() {
        beat { delay(2_000) }
        scope.advanceUntilIdle()
        assertEquals(1, finishes)
        assertEquals(2_000L, finishedAt)
        assertFalse(late)
    }

    @Test
    fun `a long one releases the broadcast at the deadline and keeps running`() {
        val done = CompletableDeferred<Unit>()
        val job = beat { delay(10 * 60_000L); done.complete(Unit) } // an install behind this check-in

        scope.advanceTimeBy(Heartbeat.DEADLINE_MS + 1); scope.runCurrent()
        assertEquals("released at the deadline", Heartbeat.DEADLINE_MS, finishedAt)
        assertTrue(late)
        assertTrue("never cancelled at the deadline", job.isActive)

        scope.advanceUntilIdle()
        assertTrue(done.isCompleted)
        assertEquals(1, finishes)
    }

    @Test
    fun `a failing heartbeat is logged and still releases the broadcast`() {
        beat { throw IOException("network down") }
        scope.advanceUntilIdle()
        assertEquals("network down", error?.message)
        assertEquals(1, finishes)
    }

    @Test
    fun `the deadline is well under the broadcast limit`() {
        // Background broadcasts get about 60s before the system treats them as an ANR.
        assertTrue(Heartbeat.DEADLINE_MS <= 40_000L)
    }
}
