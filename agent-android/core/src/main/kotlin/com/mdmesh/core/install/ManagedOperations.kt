package com.mdmesh.core.install

import com.mdmesh.policy.ManagedAppPolicy
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.policy.apps.UserAppInstallPolicy
import com.mdmesh.policy.apps.UserAppUninstallPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [InstallManager]'s managed package operations, one at a time: an install lifts only `userAppInstall`, an uninstall
 * only `userAppUninstall` ([ManagedAppPolicy.withAllowed]); a key the device does not support runs the operation as is.
 * Pure Kotlin so the routing is unit-testable.
 */
class ManagedOperations(private val toggles: Map<String, TogglePolicy>) {
    private val mutex = Mutex()

    suspend fun <T> install(operation: suspend () -> T): T = run(UserAppInstallPolicy.CAPABILITY_KEY, operation)

    suspend fun <T> uninstall(operation: suspend () -> T): T = run(UserAppUninstallPolicy.CAPABILITY_KEY, operation)

    /**
     * [ManagedAppPolicy.restoreInterrupted] on every app restriction policy (other toggles are skipped). One failure
     * does not stop the others. Returns the failures, keyed by capability key; never throws.
     */
    fun restoreInterrupted(): Map<String, Throwable> =
        toggles.values.filterIsInstance<ManagedAppPolicy>().mapNotNull { policy ->
            runCatching { policy.restoreInterrupted() }.exceptionOrNull()?.let { policy.capabilityKey to it }
        }.toMap()

    private suspend fun <T> run(key: String, operation: suspend () -> T): T = mutex.withLock {
        val policy = toggles[key] as? ManagedAppPolicy
        if (policy == null) operation() else policy.withAllowed(operation)
    }
}
