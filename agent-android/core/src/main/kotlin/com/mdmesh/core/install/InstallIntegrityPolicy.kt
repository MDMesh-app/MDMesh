package com.mdmesh.core.install

import android.content.Context
import com.mdmesh.proto.AgentCheckInResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether every `app.install` must carry a SHA-256 ([InstallRequest.integrityProblem]). The server says so on each
 * check-in ([AgentCheckInResponse.installIntegrity]); a server that predates the field always omits it, and the
 * agent then installs as before (a hash that is present is still verified), so a new agent enrolled from an older
 * server keeps working. The last value is kept across restarts.
 */
@Singleton
class InstallIntegrityPolicy(private val store: Store = Store.Memory()) {

    /** Where the server's last answer survives process death. */
    interface Store {
        fun read(): Boolean
        fun write(required: Boolean)

        class Memory(private var value: Boolean = false) : Store {
            override fun read(): Boolean = value
            override fun write(required: Boolean) { value = required }
        }
    }

    private class PrefsStore(private val context: Context) : Store {
        private val prefs by lazy { context.getSharedPreferences("mdm_install_integrity", Context.MODE_PRIVATE) }
        override fun read(): Boolean = prefs.getBoolean(KEY, false)
        override fun write(required: Boolean) { prefs.edit().putBoolean(KEY, required).apply() }
    }

    @Inject constructor(@ApplicationContext context: Context) : this(PrefsStore(context))

    @Volatile private var cached: Boolean? = null

    /** True once the server has said it puts a SHA-256 on every install it queues. */
    val required: Boolean
        get() = cached ?: store.read().also { cached = it }

    /** Records the check-in's `installIntegrity` value (null when the server doesn't send one). */
    fun update(installIntegrity: String?) {
        val now = installIntegrity == AgentCheckInResponse.INSTALL_INTEGRITY_REQUIRED
        if (now != required) {
            cached = now
            store.write(now)
        }
    }

    /** Why [req] must not be installed under the current policy, or null when it may proceed. */
    fun problem(req: InstallRequest): String? = if (required) req.integrityProblem else null

    private companion object {
        const val KEY = "required"
    }
}
