package com.mdmesh.kiosk

import android.content.Context

/**
 * SharedPreferences-backed [PinAttemptStore] ([ExitPinGate.PREFERENCE_NAME], `MODE_PRIVATE`). Commits synchronously
 * (`commit()`, not `apply()`) and returns its result, so [ExitPinGate] knows an attempt was saved before it checks it.
 */
class SharedPrefsPinAttemptStore(context: Context) : PinAttemptStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(ExitPinGate.PREFERENCE_NAME, Context.MODE_PRIVATE)

    override val failures: Int
        get() = prefs.getInt(KEY_FAILURES, 0)

    override val lastFailureAt: Long
        get() = prefs.getLong(KEY_LAST_FAILURE_AT, 0L)

    override fun write(failures: Int, lastFailureAt: Long): Boolean =
        prefs.edit()
            .putInt(KEY_FAILURES, failures)
            .putLong(KEY_LAST_FAILURE_AT, lastFailureAt)
            .commit()

    private companion object {
        const val KEY_FAILURES = "failures"
        const val KEY_LAST_FAILURE_AT = "last_failure_at"
    }
}
