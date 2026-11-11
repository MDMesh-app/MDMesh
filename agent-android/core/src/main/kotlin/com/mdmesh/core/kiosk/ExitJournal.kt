package com.mdmesh.core.kiosk

import android.content.Context

/**
 * Remembers a kiosk exit between its first and last step ([KioskApplier.exit]). The steps (leave lock task, drop the
 * allowlist and volume lock, clear the saved kiosk, release HOME) can't happen at once; if the process dies between
 * them, the device is half out of kiosk. The entry written before the first step lets the next start finish the
 * exit ([KioskApplier.resumeInterruptedExit]) instead of leaving it half done.
 */
interface ExitJournal {

    /** An exit that began and hasn't finished, with the options it was started with. */
    data class Pending(val handBackHome: Boolean, val force: Boolean)

    fun pending(): Pending?

    /** Written (synchronously) before the exit's first step. */
    fun begin(p: Pending)

    /** Cleared after its last step, or when it stopped without exiting. */
    fun end()

    /** No journal: for tests and callers that don't need resuming. */
    object None : ExitJournal {
        override fun pending(): Pending? = null
        override fun begin(p: Pending) = Unit
        override fun end() = Unit
    }
}

/** [ExitJournal] in SharedPreferences, committed at once (the process may be about to die). */
class SharedPrefsExitJournal(context: Context) : ExitJournal {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun pending(): ExitJournal.Pending? =
        if (!prefs.getBoolean(KEY_PENDING, false)) {
            null
        } else {
            ExitJournal.Pending(prefs.getBoolean(KEY_HOME, true), prefs.getBoolean(KEY_FORCE, false))
        }

    override fun begin(p: ExitJournal.Pending) {
        prefs.edit().putBoolean(KEY_PENDING, true).putBoolean(KEY_HOME, p.handBackHome)
            .putBoolean(KEY_FORCE, p.force).commit()
    }

    override fun end() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val FILE = "mdm_kiosk_exit"
        const val KEY_PENDING = "pending"
        const val KEY_HOME = "hand_back_home"
        const val KEY_FORCE = "force"
    }
}
