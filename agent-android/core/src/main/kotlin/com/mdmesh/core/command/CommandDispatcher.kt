package com.mdmesh.core.command

import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult

/**
 * Routes a [CommandEnvelope] to the [CommandHandler] registered for its `type`.
 *
 * Core compatibility guarantees (see `proto/VERSIONING.md`):
 *  - **Unknown type -> `unsupported`.** This is how an old agent tells a new server
 *    it doesn't know a command, instead of crashing.
 *  - **Expired -> `expired`.** Commands past their TTL are dropped politely.
 *  - **Thrown error -> `failed`.** A handler exception never escapes the loop.
 *
 * Idempotency: the dispatcher dedupes by `commandId` for this process's lifetime, so a command
 * fetched twice (e.g. a push wake and the reconcile floor racing within the delivered->done
 * window) runs its handler exactly once; the duplicate returns `done` without re-running.
 */
class CommandDispatcher(
    handlers: List<CommandHandler>,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    private val handlersByType: Map<String, CommandHandler> = handlers.associateBy { it.type }
    private val executed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    suspend fun dispatch(command: CommandEnvelope): CommandResult {
        if (isExpired(command)) {
            return CommandResults.expired(command, "command past ttl")
        }

        // Run each command id at most once per process (defence over server delivered-marking).
        if (!executed.add(command.commandId)) {
            return CommandResults.done(command, "duplicate ignored")
        }

        val handler = handlersByType[command.type]
            ?: return CommandResults.unsupported(command, "unknown command type: ${command.type}")

        return runCatching { handler.handle(command) }
            .getOrElse { CommandResults.failed(command, it.message ?: "handler threw") }
    }

    private fun isExpired(command: CommandEnvelope): Boolean {
        val ttl = command.ttlSeconds ?: return false
        if (ttl <= 0) return false
        val issuedAtEpoch = runCatching {
            java.time.Instant.parse(command.issuedAt).epochSecond
        }.getOrNull() ?: return false
        return nowEpochSeconds() - issuedAtEpoch > ttl
    }
}
