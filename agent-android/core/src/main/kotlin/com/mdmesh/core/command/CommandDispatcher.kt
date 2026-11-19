package com.mdmesh.core.command

import com.mdmesh.core.command.handlers.PolicyApplyHandler
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction

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
 *
 * Wiping: once a wipe is accepted ([wiping], the per-boot `FactoryResetWipeHold`), only
 * [RUN_WHILE_WIPING] runs until the device reboots or the hold ends; the rest is reported `expired`
 * (not run), not `failed` (#222). `config.apply` and `policy.apply` run: the factory reset policy
 * defers Block itself while the hold lasts, and the document is kept as the newest delivered one
 * (saved or not), so the re-apply at the hold's end puts it in force (an Allow sent meanwhile isn't
 * lost). A Block sent by `policy.apply` meanwhile is reported deferred and is not re-applied at the
 * hold's end: a `policy.apply` lasts only until the next `config.apply`, and that re-apply is one.
 * Another `device.wipe` runs: a lost device is always wiped.
 */
class CommandDispatcher(
    handlers: List<CommandHandler>,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val wiping: () -> Boolean = { false },
) {

    private val handlersByType: Map<String, CommandHandler> = handlers.associateBy { it.type }
    private val executed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    suspend fun dispatch(command: CommandEnvelope): CommandResult {
        if (isExpired(command)) {
            return CommandResults.expired(command, "command past ttl")
        }
        if (command.type !in RUN_WHILE_WIPING && wiping()) {
            return CommandResults.expired(command, NOT_RUN_WIPING)
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

    companion object {
        const val NOT_RUN_WIPING = "not run: a factory reset is under way"
        val RUN_WHILE_WIPING = setOf(DeviceAction.WIPE, DeviceAction.CONFIG_APPLY, PolicyApplyHandler.TYPE)
    }
}
