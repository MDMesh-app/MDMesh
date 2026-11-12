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

    /**
     * An exit that began and hasn't finished: its options, the [fingerprint] of the kiosk it was exiting, the [boot]
     * it was written in, how many times a start already tried to finish it ([attempts]), and whether a step threw in
     * a live process ([failed]).
     */
    data class Pending(
        val handBackHome: Boolean,
        val force: Boolean,
        val kiosk: String,
        val boot: Long = 0L,
        val attempts: Int = 0,
        val failed: Boolean = false,
    )

    fun pending(): Pending?

    /**
     * Written durably before the exit's first step. False when it could not be made durable: the caller must not go
     * on as if it were (an exit then doesn't start).
     */
    fun begin(p: Pending): Boolean

    /**
     * Cleared durably after its last step, when it stopped without exiting, or once a kiosk entered over it is
     * locked. False when the clear didn't reach the disk: the entry is then still there for the next start, which
     * settles it in kiosk (fail closed), so callers only log it.
     */
    fun end(): Boolean

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
         * (the desired state) sets a kiosk, read only when it matters. Anything unclear stays, or goes back, in kiosk.
         */
        fun decide(pending: Pending?, saved: KioskApplyPayload?, configuredOn: () -> Boolean): Resume = when {
            pending == null -> Resume.NONE
            !pending.kiosk.matches(HEX) -> Resume.DISCARD
            // A start already tried and didn't get through, or a step threw while the agent ran: never try the exit
            // again (no crash loop, no exit finished days later); settle in kiosk.
            pending.failed || pending.attempts > 0 -> if (saved != null) Resume.ROLL_BACK else Resume.DISCARD
            configuredOn() ->
                if (saved != null && pending.kiosk == fingerprint(saved)) Resume.ROLL_BACK else Resume.DISCARD
            saved == null -> Resume.FINISH // got past clearing the state: only HOME is left to release
            pending.kiosk == fingerprint(saved) -> Resume.FINISH
            else -> Resume.DISCARD
        }
    }

    /** No journal: for tests and callers that don't need resuming. */
    object None : ExitJournal {
        override fun pending(): Pending? = null
        override fun begin(p: Pending) = true
        override fun end() = true
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
                boot = runCatching { prefs.getLong(KEY_BOOT, 0L) }.getOrDefault(0L),
                attempts = runCatching { prefs.getInt(KEY_ATTEMPTS, 0) }.getOrDefault(1), // unreadable: tried
                failed = runCatching { prefs.getBoolean(KEY_FAILED, false) }.getOrDefault(true),
            )
        }

    override fun begin(p: ExitJournal.Pending): Boolean =
        prefs.edit().putBoolean(KEY_PENDING, true).putBoolean(KEY_HOME, p.handBackHome)
            .putBoolean(KEY_FORCE, p.force).putString(KEY_KIOSK, p.kiosk).putLong(KEY_BOOT, p.boot)
            .putInt(KEY_ATTEMPTS, p.attempts).putBoolean(KEY_FAILED, p.failed).commit()

    override fun end(): Boolean = prefs.edit().clear().commit()

    private companion object {
        const val FILE = "mdm_kiosk_exit"
        const val KEY_PENDING = "pending"
        const val KEY_HOME = "hand_back_home"
        const val KEY_FORCE = "force"
        const val KEY_KIOSK = "kiosk"
        const val KEY_BOOT = "boot"
        const val KEY_ATTEMPTS = "attempts"
        const val KEY_FAILED = "failed"
    }
}
