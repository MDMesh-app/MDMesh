package com.mdmesh.core.command

import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.CommandStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandDispatcherTest {

    private fun envelope(
        type: String,
        ttlSeconds: Int? = null,
        issuedAt: String = "2026-01-01T00:00:00Z",
    ) = CommandEnvelope(
        commandId = "cmd-1",
        issuedAt = issuedAt,
        ttlSeconds = ttlSeconds,
        type = type,
    )

    private class EchoHandler(override val type: String) : CommandHandler {
        override suspend fun handle(command: CommandEnvelope): CommandResult =
            CommandResults.done(command)
    }

    @Test
    fun `unknown command type returns unsupported`() = runTest {
        val dispatcher = CommandDispatcher(handlers = emptyList())

        val result = dispatcher.dispatch(envelope("totally.unknown"))

        assertEquals(CommandStatus.UNSUPPORTED, result.status)
        assertEquals("cmd-1", result.commandId)
    }

    @Test
    fun `known command type is routed to its handler`() = runTest {
        val dispatcher = CommandDispatcher(handlers = listOf(EchoHandler("config.sync")))

        val result = dispatcher.dispatch(envelope("config.sync"))

        assertEquals(CommandStatus.DONE, result.status)
    }

    @Test
    fun `expired command returns expired`() = runTest {
        // issued at epoch 0, ttl 60s, but "now" is far in the future.
        val dispatcher = CommandDispatcher(
            handlers = listOf(EchoHandler("config.sync")),
            nowEpochSeconds = { 10_000_000_000L },
        )

        val result = dispatcher.dispatch(
            envelope("config.sync", ttlSeconds = 60, issuedAt = "1970-01-01T00:00:00Z"),
        )

        assertEquals(CommandStatus.EXPIRED, result.status)
    }

    /** #222: the server can queue a config.apply in the same response as the wipe; it must not run after it. */
    @Test
    fun `runs nothing after a wipe is accepted and reports it not run, not failed`() = runTest {
        var wiping = false
        val ran = mutableListOf<String>()
        val wipe = object : CommandHandler {
            override val type = "device.wipe"
            override suspend fun handle(command: CommandEnvelope): CommandResult {
                wiping = true // as DeviceWipeHandler sets the hold just before the wipe call returns
                return CommandResults.done(command)
            }
        }
        val apply = object : CommandHandler {
            override val type = "config.apply"
            override suspend fun handle(command: CommandEnvelope): CommandResult {
                ran += command.commandId
                return CommandResults.done(command)
            }
        }
        val dispatcher = CommandDispatcher(listOf(wipe, apply), wiping = { wiping })

        val wiped = dispatcher.dispatch(envelope("device.wipe").copy(commandId = "w"))
        val skipped = dispatcher.dispatch(envelope("config.apply").copy(commandId = "c"))

        assertEquals(CommandStatus.DONE, wiped.status)
        assertEquals(CommandStatus.EXPIRED, skipped.status)
        assertEquals(CommandDispatcher.NOT_RUN_WIPING, skipped.detail)
        assertEquals(emptyList<String>(), ran)
    }

    @Test
    fun `handler exception becomes failed`() = runTest {
        val throwing = object : CommandHandler {
            override val type = "boom"
            override suspend fun handle(command: CommandEnvelope): CommandResult =
                throw IllegalStateException("kaboom")
        }
        val dispatcher = CommandDispatcher(handlers = listOf(throwing))

        val result = dispatcher.dispatch(envelope("boom"))

        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals("kaboom", result.detail)
    }
}
