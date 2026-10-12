package com.mdmesh.core.install

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests: status values are plain ints (the same values as the
 * `PackageInstaller.STATUS_*` constants) so the test needs no Android stubs.
 */
class InstallResultBusTest {

    private companion object {
        const val STATUS_SUCCESS = 0
        const val STATUS_FAILURE = 1
        const val STATUS_FAILURE_BLOCKED = 2
    }

    @Test
    fun `await returns the event published for the matching session id`() = runTest {
        val bus = InstallResultBus()
        val deferred = async { bus.await(sessionId = 42) }

        bus.publish(sessionId = 42, status = STATUS_SUCCESS, message = "ok")

        val event = deferred.await()
        assertEquals(42, event.sessionId)
        assertEquals(STATUS_SUCCESS, event.status)
        assertEquals("ok", event.message)
    }

    @Test
    fun `await ignores events for other sessions and returns only its own`() = runTest {
        val bus = InstallResultBus()
        val deferred = async { bus.await(sessionId = 2) }

        bus.publish(sessionId = 1, status = STATUS_FAILURE, message = "other")
        bus.publish(sessionId = 3, status = STATUS_FAILURE_BLOCKED, message = "another")
        bus.publish(sessionId = 2, status = STATUS_SUCCESS, message = "mine")

        val event = deferred.await()
        assertEquals(2, event.sessionId)
        assertEquals(STATUS_SUCCESS, event.status)
        assertEquals("mine", event.message)
    }

    @Test
    fun `result published before await subscribes is not dropped`() = runTest {
        val bus = InstallResultBus()
        // Publish first; the large extra buffer must retain it for a later awaiter.
        bus.publish(sessionId = 7, status = STATUS_SUCCESS, message = null)

        val event = bus.await(sessionId = 7)
        assertEquals(7, event.sessionId)
        assertEquals(STATUS_SUCCESS, event.status)
    }

    @Test
    fun `await gives up after the timeout when no result ever arrives`() = runTest {
        val bus = InstallResultBus()
        // A lost broadcast must not suspend forever: the caller holds the check-in mutex while it waits.
        assertNull(bus.await(sessionId = 9, timeoutMs = 10 * 60_000L))
        // A late result for the abandoned session must not throw.
        bus.publish(sessionId = 9, status = STATUS_FAILURE, message = "late")
    }

    @Test
    fun `a late result for a timed-out session is not handed to the next wait on the same id`() = runTest {
        // Uninstall derives its session id from the package, so the next uninstall of that package reuses it.
        val bus = InstallResultBus()
        bus.expect(-3)
        assertNull(bus.await(sessionId = -3, timeoutMs = 1_000L))
        bus.publish(sessionId = -3, status = STATUS_FAILURE, message = "late") // dropped by the abandoned mark
        bus.publish(sessionId = -3, status = STATUS_FAILURE, message = "later") // leaves a completed stale slot

        bus.expect(-3)
        val next = async { bus.await(sessionId = -3, timeoutMs = 60_000L) }
        bus.publish(sessionId = -3, status = STATUS_SUCCESS, message = "fresh")
        assertEquals("fresh", next.await()?.message)
    }

    @Test
    fun `a fast result on a reused id is kept even before the wait starts`() = runTest {
        val bus = InstallResultBus()
        bus.expect(-4)
        assertNull(bus.await(sessionId = -4, timeoutMs = 1_000L)) // leaves -4 marked abandoned

        bus.expect(-4) // the next uninstall, before the platform call
        bus.publish(sessionId = -4, status = STATUS_SUCCESS, message = "fast") // the receiver beats the await
        assertEquals("fast", bus.await(sessionId = -4, timeoutMs = 60_000L)?.message)
    }

    @Test
    fun `await with a timeout returns a result that arrives in time`() = runTest {
        val bus = InstallResultBus()
        val deferred = async { bus.await(sessionId = 5, timeoutMs = 60_000L) }
        bus.publish(sessionId = 5, status = STATUS_SUCCESS, message = null)
        assertEquals(STATUS_SUCCESS, deferred.await()?.status)
    }
}
