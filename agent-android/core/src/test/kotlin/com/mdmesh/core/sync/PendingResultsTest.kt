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

    private fun result(id: String, detail: String?) =
        CommandResult(commandId = id, status = CommandStatus.DONE, detail = detail, completedAt = "2026-10-12T00:00:00Z")

    @Test
    fun `drain hands out at most one batch by count`() {
        val pending = PendingResults(FakeBackup())
        pending.add((1..250).map { result("c$it") })

        val first = pending.drain()
        assertEquals(PendingResults.MAX_BATCH_RESULTS, first.size)
        assertEquals("c1", first.first().commandId)
        pending.delivered()
        assertEquals(PendingResults.MAX_BATCH_RESULTS, pending.drain().size)
        pending.delivered()
        assertEquals(250 - 2 * PendingResults.MAX_BATCH_RESULTS, pending.drain().size)
    }

    @Test
    fun `drain hands out at most one batch by size, but always at least one result`() {
        val pending = PendingResults(FakeBackup())
        val big = "x".repeat(PendingResults.MAX_DETAIL_CHARS - 1000)
        pending.add(listOf(result("a", big), result("b", big), result("c", big), result("d", big)))

        val first = pending.drain()
        assertTrue(first.isNotEmpty())
        assertTrue(first.sumOf { it.detail?.length ?: 0 } <= PendingResults.MAX_BATCH_CHARS)
        assertTrue("a batch carries several big results when they fit", first.size >= 1)
        pending.delivered()
        val rest = generateSequence { pending.drain().also { pending.delivered() }.takeIf { it.isNotEmpty() } }
            .flatten().map { it.commandId }.toList()
        assertEquals(listOf("a", "b", "c", "d"), (first.map { it.commandId } + rest))
    }

    @Test
    fun `an oversized result is kept as a short failure`() {
        val backup = FakeBackup()
        PendingResults(backup).add(listOf(result("huge", "x".repeat(PendingResults.MAX_DETAIL_CHARS + 1))))

        val sent = PendingResults(backup).drain().single()
        assertEquals("huge", sent.commandId)
        assertEquals(CommandStatus.FAILED, sent.status)
        assertTrue(sent.detail!!.startsWith("result too large"))
        assertTrue(backup.raw!!.length < 1000)
    }

    @Test
    fun `results the server did not accept go back to the front`() {
        val backup = FakeBackup()
        val pending = PendingResults(backup)
        pending.add(listOf(result("a"), result("b"), result("c")))
        pending.drain()
        pending.add(listOf(result("d")))

        pending.delivered(accepted = 1)

        assertEquals(listOf("b", "c", "d"), PendingResults(backup).drain().map { it.commandId })
    }
}
