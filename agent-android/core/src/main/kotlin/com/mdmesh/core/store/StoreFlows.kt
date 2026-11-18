package com.mdmesh.core.store

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException

/** The first retry's wait; doubled each time up to [STORAGE_RETRY_MAX_MS]. */
internal const val STORAGE_RETRY_FIRST_MS = 1_000L
internal const val STORAGE_RETRY_MAX_MS = 60_000L

/**
 * Keeps a store's flow alive through storage errors, so a screen collecting it never crashes on one (#220): the error
 * is logged and the store read again after a backoff ([STORAGE_RETRY_FIRST_MS] doubling to [STORAGE_RETRY_MAX_MS],
 * from the first again after a value got through).
 * Meanwhile collectors keep the last value they had; a flow whose collectors need a value to go on (a combine)
 * passes [whileFailing], whose value is emitted on each error. A corrupt file is not an error here:
 * [AgentDataStores] recovers it.
 */
internal fun <T> Flow<T>.retryOnStorageError(what: String, whileFailing: (() -> T)? = null): Flow<T> = flow {
    // Errors since the last value: a read that worked starts the backoff over.
    var failures = 0L
    emitAll(
        onEach { failures = 0 }.retryWhen { cause, _ ->
            if (cause !is IOException) return@retryWhen false
            logRetry(what, cause)
            if (whileFailing != null) emit(whileFailing())
            delay(storageRetryDelay(failures++))
            true
        },
    )
}

internal fun storageRetryDelay(attempt: Long): Long =
    (STORAGE_RETRY_FIRST_MS shl attempt.coerceAtMost(MAX_DOUBLINGS).toInt()).coerceAtMost(STORAGE_RETRY_MAX_MS)

private const val MAX_DOUBLINGS = 6L

private fun logRetry(what: String, cause: IOException) {
    runCatching { Log.w("StoreFlows", "$what unreadable, retrying", cause) }
}
