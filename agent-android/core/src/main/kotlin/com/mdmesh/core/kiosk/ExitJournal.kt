package com.mdmesh.core.kiosk

import android.content.Context
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.ProtocolJson
import java.security.MessageDigest

/**
 * Remembers a kiosk exit between its first and last step ([KioskApplier.exit]). The steps (leave lock task, drop the
 * allowlist and volume lock, clear the saved kiosk, release HOME) can't happen at once; if the process dies between
 * them, the device is half out of kiosk. The entry, written before the first step, records which kiosk was being
 * exited ([Pending.kiosk]), so the next start can settle it ([KioskApplier.resumeInterruptedExit]) without ever
 * applying it to a different kiosk.
 */
interface ExitJournal {

    /** An exit that began and hasn't finished: its options and the [fingerprint] of the kiosk it was exiting. */
    data class Pending(val handBackHome: Boolean, val force: Boolean, val kiosk: String)

    fun pending(): Pending?

    /** Written durably before the exit's first step. */
    fun begin(p: Pending)

    /** Cleared durably after its last step, when it stopped without exiting, or when a kiosk is entered. */
    fun end()

    /** What the next start does with a [Pending] exit. */
    enum class Resume {
        /** Nothing pending. */
        NONE,

        /**
         * Finish the exit: the same kiosk is still saved (or already cleared), and the configuration doesn't set a
         * kiosk (`configuredKiosk() == null`). That is the case for every kiosk from a `kiosk.enter` command: its exit
         * was authorized (the right PIN, a remote exit), so finishing it is right. Don't tighten this into "never
         * finish": a cut-off exit of a command kiosk would then relock forever.
         */
        FINISH,

        /** Lock the saved kiosk again: the configuration still sets a kiosk (fail closed). */
        ROLL_BACK,

        /**
         * Drop the entry: another kiosk since, or an entry that can't be read. The saved kiosk, if any, is locked
         * again (allowlist, lock task, HOME claim): the exit may have got past dropping the allowlist, so "stays in
         * kiosk" is made true, not assumed.
         */
        DISCARD,
    }

    companion object {
        private const val NO_KIOSK = "none"
        private val HEX = Regex("[0-9a-f]{64}")

        /** A stable identity of [kiosk] (SHA-256 of its protocol JSON), or "none". */
        fun fingerprint(kiosk: KioskApplyPayload?): String {
            if (kiosk == null) return NO_KIOSK
            val json = ProtocolJson.json.encodeToString(KioskApplyPayload.serializer(), kiosk)
            return MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        /**
         * The decision, pure: [saved] is the kiosk saved now, [configuredOn] whether the server's configuration
         * (the desired state) sets a kiosk. Anything unclear stays, or goes back, in kiosk.
         */
        fun decide(pending: Pending?, saved: KioskApplyPayload?, configuredOn: Boolean): Resume = when {
            pending == null -> Resume.NONE
            !pending.kiosk.matches(HEX) -> Resume.DISCARD
            configuredOn ->
                if (saved != null && pending.kiosk == fingerprint(saved)) Resume.ROLL_BACK else Resume.DISCARD
            saved == null -> Resume.FINISH // got past clearing the state: only HOME is left to release
            pending.kiosk == fingerprint(saved) -> Resume.FINISH
            else -> Resume.DISCARD
        }
    }

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

    /** An entry without a readable kiosk identity comes back with an empty one, which [ExitJournal.decide] discards. */
    override fun pending(): ExitJournal.Pending? =
        if (!prefs.getBoolean(KEY_PENDING, false)) {
            null
        } else {
            ExitJournal.Pending(
                handBackHome = prefs.getBoolean(KEY_HOME, true),
                force = prefs.getBoolean(KEY_FORCE, false),
                kiosk = runCatching { prefs.getString(KEY_KIOSK, null) }.getOrNull().orEmpty(),
            )
        }

    override fun begin(p: ExitJournal.Pending) {
        prefs.edit().putBoolean(KEY_PENDING, true).putBoolean(KEY_HOME, p.handBackHome)
            .putBoolean(KEY_FORCE, p.force).putString(KEY_KIOSK, p.kiosk).commit()
    }

    override fun end() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val FILE = "mdm_kiosk_exit"
        const val KEY_PENDING = "pending"
        const val KEY_HOME = "hand_back_home"
        const val KEY_FORCE = "force"
        const val KEY_KIOSK = "kiosk"
    }
}
