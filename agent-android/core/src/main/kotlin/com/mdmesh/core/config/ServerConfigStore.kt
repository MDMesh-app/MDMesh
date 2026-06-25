package com.mdmesh.core.config

import android.content.Context
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
class ServerConfigStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("mdm_server", Context.MODE_PRIVATE)

    /** Configured server base URL (no trailing slash). Provisioned value, else the baked default. */
    fun baseUrl(): String {
        val stored = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
        return (stored ?: BuildConfig.MDM_BASE_URL).trimEnd('/')
    }

    /** Persist the provisioned server URL. No-op for blank input. */
    fun save(url: String?) {
        val u = url?.trim()?.takeIf { it.isNotBlank() } ?: return
        prefs.edit().putString(KEY, u).apply()
    }

    private companion object { const val KEY = "base_url" }
}
