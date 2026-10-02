package com.mdmesh.core.config

import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.UserAppPolicy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Platform boundary. Implementations must throw when a requested OS change did not converge. */
interface AppRestrictionBackend {
    fun isSupported(): Boolean
    fun installedPackages(): Set<String>
    fun isProtectedPackage(pkg: String): Boolean
    fun unknownSourcesBlocked(): Boolean
    fun setUnknownSourcesBlocked(blocked: Boolean)
    fun isHidden(pkg: String): Boolean
    fun setHidden(pkg: String, hidden: Boolean)
    fun isUninstallBlocked(pkg: String): Boolean
    fun setUninstallBlocked(pkg: String, blocked: Boolean)
}

@Serializable
data class AppRestrictionState(
    val installBlocked: Boolean = false,
    val uninstallBlocked: Boolean = false,
    val stores: Set<String> = UserAppPolicy.DEFAULT_STORES,
    val hiddenByAgent: Set<String> = emptySet(),
    val protectedByAgent: Set<String> = emptySet(),
    val ownsUnknownSources: Boolean = false,
    val pendingRemoval: String? = null,
)

interface AppRestrictionStore {
    fun load(): AppRestrictionState
    /** Durable, synchronous write: journal before touching the OS. */
    fun save(state: AppRestrictionState)
}

/**
 * One mutex covers configuration, package events, restart recovery and managed PackageInstaller operations.
 * The durable journal preserves Auto, tracks only changes made here, and repairs interrupted removals.
 * This never adds DISALLOW_INSTALL_APPS or DISALLOW_UNINSTALL_APPS.
 */
class AppRestrictions(private val backend: AppRestrictionBackend, private val store: AppRestrictionStore) {
    private val mutex = Mutex()
    private var state = store.load()

    private fun save(next: AppRestrictionState) {
        store.save(next)
        state = next
    }

    suspend fun apply(policies: Map<String, Boolean>, stores: List<String>?): Map<String, String> = mutex.withLock {
        val requested = policies.filterKeys { it in UserAppPolicy.KEYS }
        if (!backend.isSupported()) return@withLock requested.mapValues { ConfigOutcome.UNSUPPORTED }
        val outcomes = linkedMapOf<String, String>()
        for ((key, allowed) in requested) {
            outcomes[key] = runCatching {
                recoverRemoval()
                when (key) {
                    UserAppPolicy.INSTALL -> {
                        val packages = (stores?.toSet() ?: state.stores)
                        if (!allowed) validateStores(packages)
                        save(state.copy(installBlocked = !allowed, stores = packages))
                        reconcileInstall()
                    }
                    UserAppPolicy.UNINSTALL -> {
                        save(state.copy(uninstallBlocked = !allowed))
                        reconcileUninstall()
                    }
                }
                ConfigOutcome.APPLIED
            }.getOrElse { ConfigOutcome.failed(it.message ?: "app restriction failed") }
        }
        outcomes
    }

    /** Runs even when the last config used Auto or a prior partial apply failed. */
    suspend fun reconcile() = mutex.withLock {
        if (backend.isSupported()) {
            recoverRemoval()
            reconcileInstall()
            reconcileUninstall()
        }
    }

    suspend fun <T> managedInstall(pkg: String, install: suspend () -> T): T = mutex.withLock {
        recoverRemoval()
        try {
            install()
        } finally {
            withContext(NonCancellable) {
                if (backend.isSupported() && state.uninstallBlocked && pkg in backend.installedPackages()) protect(pkg)
            }
        }
    }

    suspend fun <T> managedRemoval(pkg: String, remove: suspend () -> T): T = mutex.withLock {
        recoverRemoval()
        // Only release a block this manager owns. Pre-existing admin protection remains authoritative.
        if (pkg !in state.protectedByAgent) return@withLock remove()
        save(state.copy(pendingRemoval = pkg))
        try {
            backend.setUninstallBlocked(pkg, false)
            remove()
        } finally {
            withContext(NonCancellable) { recoverRemoval() }
        }
    }

    private fun recoverRemoval() {
        val pkg = state.pendingRemoval ?: return
        if (pkg in backend.installedPackages()) {
            // Leave the journal intact if the OS rejects restoration; next recovery retries.
            backend.setUninstallBlocked(pkg, true)
            save(state.copy(pendingRemoval = null))
        } else {
            save(state.copy(pendingRemoval = null, protectedByAgent = state.protectedByAgent - pkg))
        }
    }

    private fun validateStores(packages: Set<String>) {
        packages.forEach { pkg ->
            require(PACKAGE_NAME.matches(pkg)) { "Invalid store package: $pkg" }
            require(!backend.isProtectedPackage(pkg)) { "Cannot hide management/system component: $pkg" }
        }
    }

    private fun reconcileInstall() {
        if (state.installBlocked) validateStores(state.stores)
        if (state.installBlocked && !backend.unknownSourcesBlocked()) {
            save(state.copy(ownsUnknownSources = true))
            backend.setUnknownSourcesBlocked(true)
        } else if (!state.installBlocked && state.ownsUnknownSources) {
            backend.setUnknownSourcesBlocked(false)
            save(state.copy(ownsUnknownSources = false))
        }
        val desired = if (state.installBlocked) state.stores else emptySet()
        val installed = backend.installedPackages()
        for (pkg in state.hiddenByAgent - desired) {
            if (pkg in installed && backend.isHidden(pkg)) backend.setHidden(pkg, false)
            save(state.copy(hiddenByAgent = state.hiddenByAgent - pkg))
        }
        for (pkg in desired.intersect(installed)) {
            if (!backend.isHidden(pkg)) {
                save(state.copy(hiddenByAgent = state.hiddenByAgent + pkg))
                backend.setHidden(pkg, true)
            }
        }
    }

    private fun protect(pkg: String) {
        if (!backend.isUninstallBlocked(pkg)) {
            save(state.copy(protectedByAgent = state.protectedByAgent + pkg))
            backend.setUninstallBlocked(pkg, true)
        }
    }

    private fun reconcileUninstall() {
        val installed = backend.installedPackages()
        if (state.uninstallBlocked) {
            installed.forEach(::protect)
            save(state.copy(protectedByAgent = state.protectedByAgent.intersect(installed)))
        } else {
            for (pkg in state.protectedByAgent) {
                if (pkg in installed) backend.setUninstallBlocked(pkg, false)
                save(state.copy(protectedByAgent = state.protectedByAgent - pkg))
            }
        }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
