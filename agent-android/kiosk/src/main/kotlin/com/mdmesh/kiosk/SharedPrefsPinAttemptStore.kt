package com.mdmesh.kiosk

import android.content.Context

/**
 * SharedPreferences-backed [PinAttemptStore] ([ExitPinGate.PREFERENCE_NAME], `MODE_PRIVATE`). Commits synchronously
 * (`commit()`, not `apply()`) and returns its result, so [ExitPinGate] knows an attempt was saved before it checks it.
 * A file written by an agent before 0.5.0 has no schema key: it reads as schema 1, which [ExitPinGate] migrates.
 */
class SharedPrefsPinAttemptStore(context: Context) : PinAttemptStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(ExitPinGate.PREFERENCE_NAME, Context.MODE_PRIVATE)

    override fun read(): PinAttempts = PinAttempts(
        failures = prefs.getInt(KEY_FAILURES, 0),
        lastFailureAt = prefs.getLong(KEY_LAST_FAILURE_AT, 0L),
        pinKey = prefs.getString(KEY_PIN, null),
        schema = prefs.getInt(KEY_SCHEMA, LEGACY_SCHEMA),
    )

    override fun write(attempts: PinAttempts): Boolean =
        prefs.edit()
            .putInt(KEY_FAILURES, attempts.failures)
            .putLong(KEY_LAST_FAILURE_AT, attempts.lastFailureAt)
            .putString(KEY_PIN, attempts.pinKey)
            .putInt(KEY_SCHEMA, attempts.schema)
            .commit()

    private companion object {
        const val KEY_FAILURES = "failures"
        const val KEY_LAST_FAILURE_AT = "last_failure_at"
        const val KEY_PIN = "pin_key"
        const val KEY_SCHEMA = "schema"

        /** What a file without a schema was written by: an agent before 0.5.0. */
        const val LEGACY_SCHEMA = 1
    }
}
