package com.mdmesh.core.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.sync.CheckInWorker
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
 * [UserSetup] from `Settings.Secure.USER_SETUP_COMPLETE` (hidden, but `@Readable`, so any app may read it). What waits
 * for it (a re-apply, crash dialogs held back) is remembered in preferences, so a process killed before setup finishes
 * picks it up again in [resume], which the application calls at every start.
 *
 * @param configStore marked stale once a waiting kiosk is released, so the server sends the configuration again.
 */
class AndroidUserSetup(context: Context, private val configStore: ConfigStateStore) : UserSetup {
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

    /**
     * No crash dialogs until setup completes: `DISALLOW_SYSTEM_ERROR_DIALOGS`, set as [admin], closes a crashed app as
     * if "Close app" were tapped. Hiding an OEM's setup app ([EnrollmentHiddenApps]) makes the setup wizard crash when
     * it next loads that app's part of its script (seen on a Lenovo TB311XU). Closed, the wizard restarts on its short
     * provisioned path and finishes, skipping the hidden app's pages, but its "has stopped" dialog would wait for the
     * person enrolling. Lifted as soon as setup completes, so crashes are reported as usual afterwards.
     */
    fun quietUntilComplete(admin: ComponentName) {
        val quiet = runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS) }.isSuccess
        if (!quiet) return
        prefs.edit().putString(KEY_QUIET_ADMIN, admin.flattenToString()).apply()
        resume()
    }

    /** Re-arm a wait left by an earlier process, or finish it now if setup completed while nothing was watching. */
    fun resume() {
        if (!prefs.getBoolean(KEY_WAITING, false) && !prefs.contains(KEY_QUIET_ADMIN)) return
        synchronized(this) {
            if (observer == null) {
                // Registered before the check below, so a completion between the two is not missed.
                observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        if (isComplete()) onComplete()
                    }
                }.also {
                    val uri = Settings.Secure.getUriFor(USER_SETUP_COMPLETE)
                    context.contentResolver.registerContentObserver(uri, false, it)
                }
            }
        }
        if (isComplete()) onComplete()
    }

    private fun onComplete() {
        synchronized(this) {
            val o = observer ?: return // already fired
            context.contentResolver.unregisterContentObserver(o)
            observer = null
        }
        prefs.getString(KEY_QUIET_ADMIN, null)?.let(ComponentName::unflattenFromString)?.let { admin ->
            runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS) }
        }
        val waiting = prefs.getBoolean(KEY_WAITING, false)
        prefs.edit().remove(KEY_WAITING).remove(KEY_QUIET_ADMIN).apply()
        if (waiting) {
            // The configuration's result went to the server while setup ran, its kiosk "deferred", and the console
            // shows that until another result arrives. Stale, the next check-in has the server send the configuration
            // again, and that result has the kiosk entered. The re-apply enters it now, network or not.
            configStore.markStale()
            ConfigReapplyWorker.scheduleNow(context)
            CheckInWorker.scheduleNow(context)
        }
    }

    private val dpm get() = context.getSystemService(DevicePolicyManager::class.java)

    private companion object {
        const val USER_SETUP_COMPLETE = "user_setup_complete"
        const val PREFS = "user_setup"
        const val KEY_WAITING = "kiosk_waiting"
        const val KEY_QUIET_ADMIN = "quiet_admin"
    }
}
