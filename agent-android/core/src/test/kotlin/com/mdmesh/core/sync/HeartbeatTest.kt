package com.mdmesh.core.sync

import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.install.InstallIntegrityPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    private val wakeLock = mutableListOf<String>()

    private fun beat(work: suspend () -> Unit) = Heartbeat.launch(
        scope = scope,
        onError = { error = it },
        onLate = { late = true },
        finish = { finishes++; finishedAt = scope.currentTime; wakeLock += "finish@${scope.currentTime}" },
        keepAwake = { timeout ->
            wakeLock += "acquire($timeout)@${scope.currentTime}"
            val release = { wakeLock += "release@${scope.currentTime}"; Unit }
            release
        },
        work = work,
    )

    @Test
    fun `a quick heartbeat releases its broadcast when done, without a wakelock of its own`() {
        beat { delay(2_000) }
        scope.advanceUntilIdle()
        assertEquals(1, finishes)
        assertEquals(2_000L, finishedAt)
        assertFalse(late)
        assertEquals(listOf("finish@2000"), wakeLock)
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
    fun `work past the deadline holds a bounded wakelock from before the release until it ends`() {
        beat { delay(5 * 60_000L) }
        scope.advanceUntilIdle()
        val d = Heartbeat.DEADLINE_MS
        assertEquals(
            listOf("acquire(${Heartbeat.WAKELOCK_TIMEOUT_MS})@$d", "finish@$d", "release@${5 * 60_000L}"),
            wakeLock,
        )
    }

    @Test
    fun `the wakelock is released when the late work fails`() {
        beat { delay(Heartbeat.DEADLINE_MS + 5_000); throw IOException("network down") }
        scope.advanceUntilIdle()
        assertEquals("release@${Heartbeat.DEADLINE_MS + 5_000}", wakeLock.last())
        assertEquals("network down", error?.message)
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

    @Test
    fun `a wakelock that can't be taken is reported, and the broadcast is still released`() {
        val failing = Heartbeat.launch(
            scope = scope,
            onError = { error = it },
            onLate = { late = true },
            finish = { finishes++ },
            keepAwake = { throw SecurityException("no WAKE_LOCK") },
        ) { delay(5 * 60_000L) }
        scope.advanceUntilIdle()
        assertEquals(1, finishes)
        assertEquals("no WAKE_LOCK", error?.message)
        assertTrue(failing.isCompleted)
    }

    @Test
    fun `the heartbeat hands its check-in to a running one and waits for it`() = runTest {
        val api = FakeMdmApi().apply { checkInGate = CompletableDeferred() }
        val identity = FakeIdentity(initialId = "dev-1", initialSecret = "sek-1")
        val coordinator = CheckInCoordinator(
            api = api,
            enrollment = EnrollmentManager(api, identity, FakeTokenProvider("t"), FakeCapabilitySource(), NoopEventSink),
            identity = identity,
            capabilitySource = FakeCapabilitySource(),
            dispatcher = CommandDispatcher(emptyList()),
            pending = PendingResults(),
            stateSource = { null },
            telemetrySource = { null },
            eventSink = NoopEventSink,
            installIntegrity = InstallIntegrityPolicy(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val running = launch { coordinator.runOnce() } // an install holds the cycle
        advanceUntilIdle()

        val logs = mutableListOf<String>()
        var healed = false
        val heartbeat = launch { Heartbeat.checkIn(coordinator, { logs += it }) { healed = true } }
        advanceUntilIdle()
        assertTrue(healed)
        assertEquals("no cycle of its own", 1, api.checkInRequests.size)
        assertFalse("waits (under the heartbeat's wakelock) for the check-in that serves it", heartbeat.isCompleted)

        api.checkInGate!!.complete(Unit)
        running.join()
        heartbeat.join()
        assertEquals(2, api.checkInRequests.size)
        assertEquals(listOf("check-in already running; handed to it"), logs)
    }
}
