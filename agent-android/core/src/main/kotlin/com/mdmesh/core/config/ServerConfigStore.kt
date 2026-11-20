package com.mdmesh.core.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.mdmesh.core.BuildConfig
import com.mdmesh.core.store.AgentDataStores
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.proto.EventType
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
 *
 * Kept in two files, `mdm_server` and its mirror `mdm_server_mirror` (#220): Android reads a preferences file the
 * storage corrupted as empty, which sent an enrolled device, with its secret, to the baked default host. A lost file
 * is restored from the other one on the next read, and recorded as a [EventType.STORAGE_RECOVERED] event.
 */
@Singleton
class ServerConfigStore @Inject constructor(@ApplicationContext private val context: Context) : ServerAddress {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val mirror = context.getSharedPreferences(FILE + AgentDataStores.MIRROR_SUFFIX, Context.MODE_PRIVATE)

    /**
     * Configured server origin (no trailing slash; [ServerUrls.effective]). Provisioned value, else the baked default.
     * A value an older agent saved keeps the meaning it had then; one saved now that isn't an address is returned as
     * given, so requests fail naming it ([com.mdmesh.core.net.BaseUrlInterceptor]).
     */
    override fun baseUrl(): String {
        val saved = saved()
        return ServerUrls.effective(
            stored = saved?.url,
            legacy = (saved?.rules ?: 0) < RULES,
            fallback = BuildConfig.MDM_BASE_URL,
        )
    }

    private data class Saved(val url: String, val rules: Int)

    private fun SharedPreferences.saved(): Saved? = getString(KEY, null)?.let { Saved(it, getInt(KEY_RULES, 0)) }

    private fun SharedPreferences.edited(saved: Saved) = edit().putString(KEY, saved.url).putInt(KEY_RULES, saved.rules)

    /**
     * The saved address: from `mdm_server`, else from its mirror, which then restores it. A mirror that differs (an
     * install from before it, or a write cut off between the files) is brought up to date. Two map reads otherwise;
     * a repair is written in the background (apply), as this runs on every request and on the main thread.
     */
    private fun saved(): Saved? {
        val primary = prefs.saved()
        val copy = mirror.saved()
        when {
            primary != null && primary != copy -> mirror.edited(primary).apply()
            primary == null && copy != null -> {
                prefs.edited(copy).apply()
                Log.w(TAG, "$FILE was lost; restored from its mirror")
                runCatching { EventLog(context).record(EventType.STORAGE_RECOVERED, "$FILE from mirror") }
            }
        }
        return primary ?: copy
    }

    /**
     * Persist the provisioned server URL ([ServerUrls.toStore]): normalised when valid; absent/blank input is a no-op.
     * An address that isn't one never replaces a valid one saved earlier in provisioning, but with nothing valid
     * saved it is kept as given, so the device says the QR's address is wrong instead of trying the baked default host.
     *
     * @return whether a valid address was saved
     */
    fun save(url: String?): Boolean {
        val value = ServerUrls.toStore(url, saved()?.url) ?: return false
        val valid = ServerUrls.normalize(value) == value
        if (!valid) Log.w(TAG, "provisioned server address is not an http(s) address")
        // commit(): provisioning reads it back from another component right after (and the process may end).
        val saved = Saved(value, RULES)
        return prefs.edited(saved).commit() && mirror.edited(saved).commit() && valid
    }

    private companion object {
        const val FILE = "mdm_server"
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
