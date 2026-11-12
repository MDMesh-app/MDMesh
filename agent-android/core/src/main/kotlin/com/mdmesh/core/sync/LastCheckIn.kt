package com.mdmesh.core.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * When a check-in last reached the server (wall clock; null = never), behind [SyncStatus]. Persisted, so a reboot
 * does not forget it: the `wifiRadio` self-heal measures "the server is unreachable" from it.
 */
interface LastCheckIn {
    var okAtMs: Long?

    /** [okAtMs] now, then each time it changes (a status screen follows check-ins with it). */
    val okAtMsChanges: Flow<Long?>
}

/** Not persisted: for tests. */
class InMemoryLastCheckIn : LastCheckIn {
    private val value = MutableStateFlow<Long?>(null)
    override var okAtMs: Long?
        get() = value.value
        set(v) { value.value = v }
    override val okAtMsChanges: Flow<Long?> = value
}

class SharedPrefsLastCheckIn(context: Context) : LastCheckIn {
    private val prefs = context.applicationContext.getSharedPreferences("mdm_sync", Context.MODE_PRIVATE)
    override var okAtMs: Long?
        get() = prefs.getLong(KEY_OK, -1L).takeIf { it >= 0 }
        set(v) { prefs.edit().putLong(KEY_OK, v ?: -1L).apply() }

    // SharedPreferences notifies listeners on the main thread after apply(); the listener lives while collected.
    override val okAtMsChanges: Flow<Long?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_OK) trySend(okAtMs)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(okAtMs)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()
    private companion object { const val KEY_OK = "lastCheckInOk" }
}
