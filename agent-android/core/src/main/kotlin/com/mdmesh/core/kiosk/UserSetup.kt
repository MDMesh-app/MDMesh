package com.mdmesh.core.kiosk

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.mdmesh.core.sync.ConfigReapplyWorker

/**
 * Android's setup wizard, which a kiosk must not cover before it has finished.
 *
 * QR provisioning hands over to the agent partway through the setup wizard: once `ADMIN_POLICY_COMPLIANCE` returns,
 * the wizard goes on to its remaining pages (navigation mode, the OEM's own). The device enrolls and receives its
 * configuration within seconds, so a kiosk entered then covers the wizard before it finishes. The wizard never
 * records setup as complete and resumes at every boot until someone finishes it, which nobody can do from inside
 * the kiosk. A Device Owner cannot mark setup complete itself (`user_setup_complete` is not on
 * `DevicePolicyManager.setSecureSetting`'s allowlist), so the kiosk waits instead: `config.apply` defers it until
 * [isComplete] (ConfigApplier), and [reapplyWhenComplete] re-applies the configuration the moment it is.
 */
interface UserSetup {
    /** True once the setup wizard has finished. */
    fun isComplete(): Boolean

    /** Re-apply the persisted configuration once setup completes, even across a process restart. Idempotent. */
    fun reapplyWhenComplete()

    /** A device whose setup is always complete: tests, and callers that never wait. */
    object Complete : UserSetup {
        override fun isComplete() = true
        override fun reapplyWhenComplete() = Unit
    }

    companion object {
        /** The reason reported for a kiosk waiting on the setup wizard. */
        const val INCOMPLETE = "setup wizard not finished: kiosk enters when it is"
    }
}

/**
 * [UserSetup] from `Settings.Secure.USER_SETUP_COMPLETE` (hidden, but `@Readable`, so any app may read it). The wait
 * is remembered in preferences, so a process killed before setup finishes picks it up again in [resume], which the
 * application calls at every start.
 */
class AndroidUserSetup(context: Context) : UserSetup {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var observer: ContentObserver? = null // guarded by this

    /** Unreadable or absent reads as complete: the kiosk then enters as it always has, never not at all. */
    override fun isComplete(): Boolean =
        runCatching { Settings.Secure.getInt(context.contentResolver, USER_SETUP_COMPLETE, 1) == 1 }.getOrDefault(true)

    override fun reapplyWhenComplete() {
        prefs.edit().putBoolean(KEY_WAITING, true).apply()
        resume()
    }

    /** Re-arm a wait left by an earlier process, or re-apply now if setup finished while nothing was watching. */
    fun resume() {
        if (!prefs.getBoolean(KEY_WAITING, false)) return
        synchronized(this) {
            if (observer == null) {
                // Registered before the check below, so a completion between the two is not missed.
                observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        if (isComplete()) reapply()
                    }
                }.also {
                    val uri = Settings.Secure.getUriFor(USER_SETUP_COMPLETE)
                    context.contentResolver.registerContentObserver(uri, false, it)
                }
            }
        }
        if (isComplete()) reapply()
    }

    private fun reapply() {
        synchronized(this) {
            val o = observer ?: return // already fired
            context.contentResolver.unregisterContentObserver(o)
            observer = null
        }
        prefs.edit().remove(KEY_WAITING).apply()
        ConfigReapplyWorker.scheduleNow(context)
    }

    private companion object {
        const val USER_SETUP_COMPLETE = "user_setup_complete"
        const val PREFS = "user_setup"
        const val KEY_WAITING = "kiosk_waiting"
    }
}
