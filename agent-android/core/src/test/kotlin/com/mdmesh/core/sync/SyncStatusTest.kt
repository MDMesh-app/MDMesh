package com.mdmesh.core.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStatusTest {
    @Test
    fun `a successful check-in is announced`() = runTest {
        val status = SyncStatus()
        var announced = false
        val listener = launch { status.successes.first(); announced = true }
        testScheduler.runCurrent()
        status.recordSuccess()
        testScheduler.advanceUntilIdle()
        assertTrue(announced)
        listener.cancel()
    }
}
