package com.mdmesh.core.sync

import android.content.Context

/**
 * When a check-in last reached the server (wall clock; null = never), behind [SyncStatus]. Persisted, so a reboot
 * does not forget it: the `wifiRadio` self-heal measures "the server is unreachable" from it.
 */
interface LastCheckIn {
    var okAtMs: Long?
}

/** Not persisted: for tests. */
class InMemoryLastCheckIn : LastCheckIn {
    override var okAtMs: Long? = null
}

class SharedPrefsLastCheckIn(context: Context) : LastCheckIn {
    private val prefs = context.applicationContext.getSharedPreferences("mdm_sync", Context.MODE_PRIVATE)
    override var okAtMs: Long?
        get() = prefs.getLong(KEY_OK, -1L).takeIf { it >= 0 }
        set(v) { prefs.edit().putLong(KEY_OK, v ?: -1L).apply() }
    private companion object { const val KEY_OK = "lastCheckInOk" }
}
