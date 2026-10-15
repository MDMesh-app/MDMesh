package com.mdmesh.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last check-in/enroll failure, for surfacing on the status screen. [CheckInCoordinator]
 * records it on every failed cycle and clears it on the next successful one, so an operator
 * staring at "Enrolling…" can see *why* it isn't progressing without adb.
 *
 * It also remembers when a check-in last succeeded ([lastSuccessAtMs], persisted).
 */
@Singleton
class SyncStatus @Inject constructor(private val lastCheckIn: LastCheckIn) {

    /** Not persisted: for tests. */
    constructor() : this(InMemoryLastCheckIn())

    data class Failure(val message: String, val atMillis: Long)

    private val _lastError = MutableStateFlow<Failure?>(null)
    val lastError: StateFlow<Failure?> = _lastError

    fun recordFailure(t: Throwable) {
        _lastError.value = Failure(t.message ?: t.javaClass.simpleName, System.currentTimeMillis())
    }

    /** Wall clock of the last successful check-in; null when none yet. */
    val lastSuccessAtMs: Long? get() = lastCheckIn.okAtMs

    fun recordSuccess() {
        _lastError.value = null
        lastCheckIn.okAtMs = System.currentTimeMillis()
    }
}
