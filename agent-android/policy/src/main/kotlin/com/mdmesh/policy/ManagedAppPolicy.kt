package com.mdmesh.policy

/** App restriction whose temporary exception is owned by a managed package operation. */
interface ManagedAppPolicy : TogglePolicy {
    /** Lift only this restriction and restore it after completion, failure or cancellation. */
    suspend fun <T> withAllowed(operation: suspend () -> T): T
}
