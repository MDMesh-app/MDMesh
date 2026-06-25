package com.mdmesh.kiosk

import android.content.Context

/**
 * SharedPreferences-backed [FaultStore]. Uses [CrashLoopGuard.FAULT_PREFERENCE_NAME]
 * in `MODE_PRIVATE` and commits synchronously (`commit()`, not `apply()`) because the
 * process is often mid-crash when a fault is registered.
 */
class SharedPrefsFaultStore(context: Context) : FaultStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(CrashLoopGuard.FAULT_PREFERENCE_NAME, Context.MODE_PRIVATE)

    override val counter: Int
        get() = prefs.getInt(KEY_COUNTER, 0)

    override val lastFaultTime: Long
        get() = prefs.getLong(KEY_LAST_FAULT_TIME, -1L) // -1 == never faulted

    override fun write(counter: Int, lastFaultTime: Long) {
        prefs.edit()
            .putInt(KEY_COUNTER, counter)
            .putLong(KEY_LAST_FAULT_TIME, lastFaultTime)
            .commit()
    }

    private companion object {
        const val KEY_COUNTER = "fault_counter"
        const val KEY_LAST_FAULT_TIME = "last_fault_time"
    }
}
