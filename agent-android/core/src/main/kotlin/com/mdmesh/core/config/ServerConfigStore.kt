package com.mdmesh.core.config

import android.content.Context
import android.util.Log
import com.mdmesh.core.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The server base URL the agent talks to. Captured at provisioning from the QR bundle
 * (`com.mdmesh.SERVER_URL`) so ONE signed APK works for any deployment; falls back to the
 * baked [BuildConfig.MDM_BASE_URL] for dev/ADB enrollment where there is no bundle.
 *
 * SharedPreferences-backed (not DataStore) because it's read synchronously on every request (the
 * OkHttp base-URL interceptor) and on each WebSocket connect. Usable via Hilt OR constructed
 * directly (the DPC's [com.mdmesh.agent.admin.AdminReceiver] has no Hilt graph).
 */
@Singleton
class ServerConfigStore @Inject constructor(@ApplicationContext context: Context) : ServerAddress {
    private val prefs = context.getSharedPreferences("mdm_server", Context.MODE_PRIVATE)

    /**
     * Configured server origin (no trailing slash; [ServerUrls.effective]). Provisioned value, else the baked default.
     * A value an older agent saved keeps the meaning it had then; one saved now that isn't an address is returned as
     * given, so requests fail naming it ([com.mdmesh.core.net.BaseUrlInterceptor]).
     */
    override fun baseUrl(): String = ServerUrls.effective(
        stored = prefs.getString(KEY, null),
        legacy = prefs.getInt(KEY_RULES, 0) < RULES,
        fallback = BuildConfig.MDM_BASE_URL,
    )

    /**
     * Persist the provisioned server URL ([ServerUrls.toStore]): normalised when valid; absent/blank input is a no-op.
     * An address that isn't one never replaces a valid one saved earlier in provisioning, but with nothing valid saved it
     * is kept as given, so the device says the QR's address is wrong instead of trying the baked default host.
     *
     * @return whether a valid address was saved
     */
    fun save(url: String?): Boolean {
        val value = ServerUrls.toStore(url, prefs.getString(KEY, null)) ?: return false
        val valid = ServerUrls.normalize(value) == value
        if (!valid) Log.w(TAG, "provisioned server address is not an http(s) address")
        // commit(): provisioning reads it back from another component right after (and the process may end).
        return prefs.edit().putString(KEY, value).putInt(KEY_RULES, RULES).commit() && valid
    }

    private companion object {
        const val KEY = "base_url"
        /** Which rules saved [KEY]: absent (0) = an agent before [ServerUrls]; [RULES] = saved by [save]. */
        const val KEY_RULES = "base_url_rules"
        const val RULES = 1
        const val TAG = "ServerConfigStore"
    }
}

/** The server address the agent uses; an interface so enroll logic and its tests need no Android. */
fun interface ServerAddress {
    fun baseUrl(): String
}
