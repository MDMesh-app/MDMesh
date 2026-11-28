package com.mdmesh.core.install

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The packages a running check-in batch is about to install or is installing (its `app.install` commands), from
 * the moment the batch starts until it ends. The kiosk launcher reads it to tell an app on its way (enrollment sends
 * the kiosk's app with the configuration that pins it) from a missing one: it waits on its splash for the first,
 * and says the second isn't installed.
 */
@Singleton
class PendingInstalls @Inject constructor() {
    private val pending = MutableStateFlow<Set<String>>(emptySet())

    val packages: StateFlow<Set<String>> = pending.asStateFlow()

    fun add(packageNames: Collection<String>) = pending.update { it + packageNames }

    fun remove(packageNames: Collection<String>) = pending.update { it - packageNames.toSet() }

    operator fun contains(packageName: String): Boolean = packageName in pending.value
}
