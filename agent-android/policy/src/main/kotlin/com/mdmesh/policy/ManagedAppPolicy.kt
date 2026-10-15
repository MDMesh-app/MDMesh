package com.mdmesh.policy

/** App restriction whose temporary exception is owned by a managed package operation. */
interface ManagedAppPolicy : TogglePolicy {
    /** Lift only this restriction and restore it after completion, failure or cancellation. */
    suspend fun <T> withAllowed(operation: suspend () -> T): T

    /**
     * At agent start: put back a restriction that a process death left lifted mid-operation (see [LiftRestoreStore]).
     * A no-op when nothing was interrupted or an operation is running. Returns true when it restored something.
     */
    fun restoreInterrupted(): Boolean
}
