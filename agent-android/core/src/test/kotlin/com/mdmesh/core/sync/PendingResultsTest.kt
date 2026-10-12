package com.mdmesh.core.sync

import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.CommandStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingResultsTest {

    /** Stands in for SharedPreferences: survives a "process restart" (a new PendingResults on the same backup). */
    private class FakeBackup : PendingResults.Backup {
        var raw: String? = null
        override fun read(): String? = raw
        override fun write(raw: String?) { this.raw = raw }
    }

    private fun result(id: String) =
        CommandResult(commandId = id, status = CommandStatus.DONE, completedAt = "2026-10-12T00:00:00Z")

    @Test
    fun `results survive a process restart`() {
        val backup = FakeBackup()
        PendingResults(backup).add(listOf(result("a"), result("b")))

        assertEquals(listOf("a", "b"), PendingResults(backup).drain().map { it.commandId })
    }

    @Test
    fun `drained results stay persisted until the check-in that carried them succeeded`() {
        val backup = FakeBackup()
        val before = PendingResults(backup)
        before.add(listOf(result("a")))
        before.drain() // the process dies while this check-in is in flight

        val after = PendingResults(backup)
        assertEquals(listOf("a"), after.drain().map { it.commandId })
        after.delivered()
        assertTrue(PendingResults(backup).drain().isEmpty())
    }

    @Test
    fun `restore puts drained results back in front`() {
        val backup = FakeBackup()
        val pending = PendingResults(backup)
        pending.add(listOf(result("a")))
        val acks = pending.drain()
        pending.add(listOf(result("b")))
        pending.restore(acks)

        assertEquals(listOf("a", "b"), PendingResults(backup).drain().map { it.commandId })
    }

    @Test
    fun `unreadable stored results are dropped, not fatal`() {
        val backup = FakeBackup().apply { raw = "not json" }
        assertTrue(PendingResults(backup).drain().isEmpty())
    }
}
