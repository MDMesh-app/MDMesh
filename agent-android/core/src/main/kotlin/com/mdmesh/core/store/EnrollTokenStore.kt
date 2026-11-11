package com.mdmesh.core.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mdmesh.core.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Supplies the single-use enrollment token presented on `/enroll`.
 *
 * Order of precedence:
 *  1. A token captured from the QR provisioning bundle (written by the DPC's
 *     `onProfileProvisioningComplete`) and stored here.
 *  2. [BuildConfig.DEV_ENROLL_TOKEN] — a dev/CI fallback for ADB enrollment, where
 *     there is no provisioning bundle. Empty in production builds.
 *
 * Per ADR 0002/0005 there is NO global shared secret baked into the APK; the token is
 * per-enrollment and server-minted.
 */
interface EnrollTokenProvider {
    suspend fun token(): String?

    /**
     * The id of the enrollment attempt made with [token], created and stored on first use and the same on every later
     * call for that token (across process death). Null when it can't be kept.
     */
    suspend fun enrollAttempt(token: String): String? = null

    /** Forget the enrollment attempt once the device has checked in: it can resume nothing any more. */
    suspend fun clearEnrollAttempt() {}

    /** Remember that the server which enrolled with the held attempt resumes enrollments (resume-only recovery). */
    suspend fun markAttemptResumable() {}

    /**
     * The attempt id held for [token] when the server that took it resumes enrollments, without making one; null
     * otherwise (no attempt, or an older server, where a recovery enroll could make a new device).
     */
    suspend fun resumableAttempt(token: String): String? = null
}

private val Context.enrollTokenDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mdm_enroll",
)

class EnrollTokenStore(private val context: Context) : EnrollTokenProvider {

    override suspend fun token(): String? {
        val stored = context.enrollTokenDataStore.data
            .map { it[KEY_TOKEN] }
            .first()
        return stored?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEV_ENROLL_TOKEN.takeIf { it.isNotBlank() }
    }

    suspend fun save(token: String) {
        context.enrollTokenDataStore.edit { it[KEY_TOKEN] = token }
    }

    override suspend fun enrollAttempt(token: String): String? {
        var attempt: String? = null
        // One edit: read and (when new) write together, so two callers can never store different ids.
        context.enrollTokenDataStore.edit { prefs ->
            val (id, isNew) = EnrollAttempts.resolve(prefs[KEY_ATTEMPT_FOR], prefs[KEY_ATTEMPT], token) {
                EnrollAttempts.newId()
            }
            if (isNew) {
                prefs[KEY_ATTEMPT] = id
                prefs[KEY_ATTEMPT_FOR] = EnrollAttempts.bindingOf(token)
            }
            attempt = id
        }
        return attempt
    }

    override suspend fun markAttemptResumable() {
        context.enrollTokenDataStore.edit { it[KEY_ATTEMPT_RESUMABLE] = true }
    }

    override suspend fun resumableAttempt(token: String): String? {
        val prefs = context.enrollTokenDataStore.data.first()
        val attempt = prefs[KEY_ATTEMPT]
        val held = !attempt.isNullOrBlank() && prefs[KEY_ATTEMPT_FOR] == EnrollAttempts.bindingOf(token)
        return attempt.takeIf { held && prefs[KEY_ATTEMPT_RESUMABLE] == true }
    }

    override suspend fun clearEnrollAttempt() {
        context.enrollTokenDataStore.edit {
            it.remove(KEY_ATTEMPT)
            it.remove(KEY_ATTEMPT_FOR)
            it.remove(KEY_ATTEMPT_RESUMABLE)
        }
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("enroll_token")
        val KEY_ATTEMPT = stringPreferencesKey("enroll_attempt")
        val KEY_ATTEMPT_FOR = stringPreferencesKey("enroll_attempt_for")
        val KEY_ATTEMPT_RESUMABLE = booleanPreferencesKey("enroll_attempt_resumable")
    }
}
