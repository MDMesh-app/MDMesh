package com.mdmesh.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialsTest {

    @Test
    fun `credentials an enrolling check-in saves are picked up when it succeeds`() = runTest {
        // A service the heartbeat revived before the device ever enrolled: the heartbeat's own check-in enrolls it.
        val checkIns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var stored: Pair<String?, String?> = null to null
        val seen = mutableListOf<Credentials>()
        val watch = launch(Dispatchers.Unconfined) { Credentials.changes(checkIns) { stored }.collect { seen += it } }

        checkIns.tryEmit(Unit) // a check-in that did not enroll (still nothing stored)
        stored = "dev-1" to null
        checkIns.tryEmit(Unit) // half-saved never counts
        assertEquals(emptyList<Credentials>(), seen)

        stored = "dev-1" to "sek"
        checkIns.tryEmit(Unit)
        assertEquals(listOf(Credentials("dev-1", "sek")), seen)
        watch.cancel()
    }

    @Test
    fun `a recovered secret is picked up, and every success reports what is saved`() = runTest {
        // A refused enrollment recovered by resuming it: same device, the secret the server holds. Unchanged
        // credentials are reported again: the collector compares them with what it holds, so a stale value it was
        // handed elsewhere is corrected at the next success (CredentialsFollowerTest).
        val checkIns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var stored: Pair<String?, String?> = "dev-1" to "sek-dead"
        val seen = mutableListOf<Credentials>()
        val watch = launch(Dispatchers.Unconfined) { Credentials.changes(checkIns) { stored }.collect { seen += it } }

        checkIns.tryEmit(Unit)
        stored = "dev-1" to "sek-live"
        checkIns.tryEmit(Unit)
        checkIns.tryEmit(Unit) // every heartbeat succeeds; nothing changed

        val dead = Credentials("dev-1", "sek-dead")
        val live = Credentials("dev-1", "sek-live")
        assertEquals(listOf(dead, live, live), seen)
        watch.cancel()
    }

    @Test
    fun `nothing is read before a check-in succeeds`() = runTest {
        // The service reads the identity itself after its first sync; this only follows check-ins.
        val checkIns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var reads = 0
        val watch = launch(Dispatchers.Unconfined) {
            Credentials.changes(checkIns) { reads++; "dev-1" to "sek" }.collect {}
        }
        assertEquals(0, reads)
        checkIns.tryEmit(Unit)
        assertEquals(1, reads)
        watch.cancel()
    }
}
