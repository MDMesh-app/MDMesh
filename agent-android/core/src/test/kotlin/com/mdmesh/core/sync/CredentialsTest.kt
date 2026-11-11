package com.mdmesh.core.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CredentialsTest {

    @Test
    fun `stored credentials are returned at once`() = runTest {
        val checkIns = MutableSharedFlow<Unit>()
        assertEquals(Credentials("dev-1", "sek"), Credentials.await(checkIns) { "dev-1" to "sek" })
    }

    @Test
    fun `credentials an enrolling check-in saves are picked up when it succeeds`() = runTest {
        // A service the heartbeat revived before the device ever enrolled: the heartbeat's own check-in enrolls it.
        val checkIns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var stored: Pair<String?, String?> = null to null
        val got = async { Credentials.await(checkIns) { stored } }
        testScheduler.advanceUntilIdle()
        assertFalse(got.isCompleted)

        checkIns.tryEmit(Unit) // a check-in that did not enroll (still nothing stored)
        testScheduler.advanceUntilIdle()
        assertFalse(got.isCompleted)

        stored = "dev-1" to "sek"
        checkIns.tryEmit(Unit)
        assertEquals(Credentials("dev-1", "sek"), got.await())
    }
}
