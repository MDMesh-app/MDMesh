package com.mdmesh.core.install

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
