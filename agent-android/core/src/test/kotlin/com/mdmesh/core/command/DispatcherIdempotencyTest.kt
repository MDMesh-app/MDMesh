package com.mdmesh.core.command

import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DispatcherIdempotencyTest {

    @Test
    fun runsHandlerOncePerCommandId() = runBlocking {
        val runs = AtomicInteger(0)
        val handler = object : CommandHandler {
            override val type = "x.test"
            override suspend fun handle(command: CommandEnvelope): com.mdmesh.proto.CommandResult {
                runs.incrementAndGet()
                return CommandResults.done(command)
            }
        }
        val dispatcher = CommandDispatcher(listOf(handler))
        val cmd = CommandEnvelope(commandId = "42", issuedAt = "2026-01-01T00:00:00Z", type = "x.test")

        val first = dispatcher.dispatch(cmd)
        val second = dispatcher.dispatch(cmd)

        assertEquals(1, runs.get())
        assertEquals(CommandStatus.DONE, first.status)
        assertEquals(CommandStatus.DONE, second.status)
        assertEquals("duplicate ignored", second.detail)
    }
}
