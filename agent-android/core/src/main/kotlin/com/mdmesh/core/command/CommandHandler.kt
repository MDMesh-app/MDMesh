package com.mdmesh.core.command

import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult

/**
 * Handles exactly one command [type] (see `proto/registry.md` § command types).
 *
 * Keeping one small handler per type — instead of a god-class switch — is a
 * deliberate anti-pattern-avoidance choice: a new command is a new file, and the
 * dispatcher discovers it by its [type] key. Handlers return a [CommandResult] and
 * must never throw across this boundary; a thrown error becomes a `failed` result.
 */
interface CommandHandler {

    /** The single command type this handler services, e.g. `device.lock`. */
    val type: String

    suspend fun handle(command: CommandEnvelope): CommandResult
}
