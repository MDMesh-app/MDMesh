package com.mdmesh.core.command

import com.mdmesh.policy.factoryreset.FactoryResetWipeHold
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.CommandStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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

    /** #222: only the configuration and another wipe run after an accepted wipe; the rest is reported not run. */
    @Test
    fun `runs no other command after a wipe is accepted and reports it not run, not failed`() = runTest {
        var wiping = false
        val ran = mutableListOf<String>()
        val wipe = object : CommandHandler {
            override val type = "device.wipe"
            override suspend fun handle(command: CommandEnvelope): CommandResult {
                wiping = true // as DeviceWipeHandler sets the hold just before the wipe call returns
                return CommandResults.done(command)
            }
        }
        val enter = object : CommandHandler {
            override val type = "kiosk.enter"
            override suspend fun handle(command: CommandEnvelope): CommandResult {
                ran += command.commandId
                return CommandResults.done(command)
            }
        }
        val dispatcher = CommandDispatcher(listOf(wipe, enter), wiping = { wiping })

        val wiped = dispatcher.dispatch(envelope("device.wipe").copy(commandId = "w"))
        val skipped = dispatcher.dispatch(envelope("kiosk.enter").copy(commandId = "k"))

        assertEquals(CommandStatus.DONE, wiped.status)
        assertEquals(CommandStatus.EXPIRED, skipped.status)
        assertEquals(CommandDispatcher.NOT_RUN_WIPING, skipped.detail)
        assertEquals(emptyList<String>(), ran)
    }

    /**
     * PR #227 review: the configuration still applies during the hold (the factory reset policy defers Block itself,
     * and the document is saved for the expiry's re-apply), and a repeat wipe of a lost device isn't refused.
     */
    @Test
    fun `config apply, policy apply and a repeat wipe still run during the hold`() = runTest {
        val types = listOf("config.apply", "policy.apply", "device.wipe")
        val dispatcher = CommandDispatcher(types.map(::EchoHandler), wiping = { true })

        val results = types.map { dispatcher.dispatch(envelope(it).copy(commandId = it)) }

        assertEquals(types.map { CommandStatus.DONE }, results.map { it.status })
    }

    /** A wipe that hasn't rebooted the device within the bound isn't coming: commands run again. */
    @Test
    fun `runs commands again once the wipe hold expires`() = runTest {
        var nowMs = 0L
        var expired = 0
        val hold = FactoryResetWipeHold(nowMs = { nowMs }, onExpired = { expired++ })
        val dispatcher = CommandDispatcher(listOf(EchoHandler("device.lock")), wiping = { hold.held })
        hold.hold()

        nowMs = FactoryResetWipeHold.MAX_HOLD_MS - 1
        val within = dispatcher.dispatch(envelope("device.lock").copy(commandId = "a"))
        nowMs = FactoryResetWipeHold.MAX_HOLD_MS
        val after = dispatcher.dispatch(envelope("device.lock").copy(commandId = "b"))

        assertEquals(CommandStatus.EXPIRED, within.status)
        assertEquals(CommandStatus.DONE, after.status)
        assertEquals(1, expired)
    }

    private class FuncHandler(override val type: String, val run: suspend (CommandEnvelope) -> CommandResult) :
        CommandHandler {
        override suspend fun handle(command: CommandEnvelope) = run(command)
    }

    /** Dispatches [types] one after the other in a job that is stopped while the first is running. */
    private fun TestScope.dispatchStopped(dispatcher: CommandDispatcher, vararg types: String): List<CommandResult> {
        val results = mutableListOf<CommandResult>()
        val job = launch { types.forEach { results += dispatcher.dispatch(envelope(it).copy(commandId = it)) } }
        testScheduler.runCurrent()
        job.cancel()
        testScheduler.advanceUntilIdle()
        return results
    }

    /** Nothing sends them again: what a stopped check-in can still run must not be dropped for what it can't. */
    @Test
    fun `a stop ends the commands that wait, and the others in the batch still run`() = runTest {
        val ran = mutableListOf<String>()
        fun echo(type: String) = FuncHandler(type) { ran += type; CommandResults.done(it) }
        val others = listOf("device.passcodeReset", "device.lock", "device.wipe")
        val waiting = listOf("app.install", "app.uninstall").map { FuncHandler(it) { awaitCancellation() } }

        val results = dispatchStopped(
            CommandDispatcher(waiting + others.map(::echo)), "app.install", "app.uninstall", *others.toTypedArray(),
        )

        assertEquals(others, ran)
        val interrupted = CommandStatus.EXPIRED to CommandDispatcher.INTERRUPTED
        assertEquals(
            listOf(interrupted, interrupted) + others.map { CommandStatus.DONE to null },
            results.map { it.status to it.detail },
        )
    }

    @Test
    fun `a command that ended despite the stop keeps its own result`() = runTest {
        for (failing in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val handler = FuncHandler("kiosk.enter") {
                runCatching { gate.await() } // stopped here, then it ends on its own
                check(!failing) { "refused" }
                CommandResults.done(it)
            }
            val result = dispatchStopped(CommandDispatcher(listOf(handler)), "kiosk.enter").single()
            assertEquals(if (failing) CommandStatus.FAILED else CommandStatus.DONE, result.status)
        }
    }

    @Test
    fun `a handler's own time limit is its failure, not a stop`() = runTest {
        val handler = FuncHandler("app.install") { withTimeout(1_000) { awaitCancellation() } }
        assertEquals(CommandStatus.FAILED, CommandDispatcher(listOf(handler)).dispatch(envelope("app.install")).status)
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
