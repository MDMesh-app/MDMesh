package com.mdmesh.policy

import android.content.Context
import android.content.SharedPreferences

/**
 * The value an app restriction had before a managed install/uninstall lifted it ([TemporaryUserRestrictionPolicy]),
 * per capability key. Written before the lift and removed only once that value is back on the device, so a process
 * death in between (crash, low-memory kill, the agent replacing itself) is repaired at the next agent start by
 * [ManagedAppPolicy.restoreInterrupted].
 */
interface LiftRestoreStore {
    fun get(key: String): Boolean?
    fun put(key: String, blocked: Boolean)
    fun remove(key: String)
}

/** Not persisted: for tests and devices without a store. */
class InMemoryLiftRestoreStore : LiftRestoreStore {
    private val values = HashMap<String, Boolean>()
    override fun get(key: String): Boolean? = synchronized(values) { values[key] }
    override fun put(key: String, blocked: Boolean) { synchronized(values) { values[key] = blocked } }
    override fun remove(key: String) { synchronized(values) { values.remove(key) } }
}

/** [LiftRestoreStore] in SharedPreferences (commit, not apply: it must be on disk before the restriction is lifted). */
class SharedPrefsLiftRestoreStore(private val prefs: SharedPreferences) : LiftRestoreStore {
    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences("mdm_app_restriction_lift", Context.MODE_PRIVATE))

    override fun get(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, true) else null
    override fun put(key: String, blocked: Boolean) {
        check(prefs.edit().putBoolean(key, blocked).commit()) { "could not record $key before lifting it" }
    }
    override fun remove(key: String) { prefs.edit().remove(key).commit() }
}
