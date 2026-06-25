package com.mdmesh.core.action

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import com.mdmesh.policy.wifi.DpmHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the DPM reset-password token. `resetPasswordWithToken` (API 26+) needs a token registered
 * once via `setResetPasswordToken`; we generate it at enrollment and persist the bytes locally.
 */
@Singleton
class ResetPasswordTokenStore @Inject constructor(
    @ApplicationContext context: Context,
    private val handle: DpmHandle,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mdm_reset_token", Context.MODE_PRIVATE)

    /** Generate + register the token once. No-op below API 26. Returns the token bytes (or empty). */
    fun ensureToken(): ByteArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ByteArray(0)
        token()?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ok = runCatching { handle.dpm.setResetPasswordToken(handle.admin, bytes) }.getOrDefault(false)
        if (ok) prefs.edit().putString(KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        return if (ok) bytes else ByteArray(0)
    }

    fun token(): ByteArray? = prefs.getString(KEY, null)
        ?.let { Base64.decode(it, Base64.NO_WRAP) }

    private companion object { const val KEY = "token" }
}
