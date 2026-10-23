package com.mdmesh.kiosk

import java.security.MessageDigest
import java.text.Normalizer

/**
 * The kiosk's on-device exit: whether there is one, and whether an entered PIN opens it.
 *
 * - **No PIN, no exit.** A kiosk without an exit PIN has no on-device exit at all; it ends only from the console.
 * - **Wrong PINs lock the exit.** [FREE_ATTEMPTS] wrong PINs in a row lock it for the first of [LOCKOUTS_MS]. While
 *   it is locked no PIN is checked, right or wrong. Each wrong PIN after a lockout locks it again for the next, longer
 *   step (the last step repeats), so guessing an 8-digit PIN takes lifetimes. A right PIN clears the count.
 * - **Counted before it is checked.** Every attempt is saved before the PIN is compared, so ending the app mid-check
 *   or a store that can't write never hands out a free guess (the attempt is refused instead).
 * - **Restarts and clocks don't help.** Lockouts are timed on [elapsedMs], a clock that only moves forward and can't
 *   be set (Android's `elapsedRealtime`). It starts again from zero at a reboot, which the gate notices and treats as
 *   the start of the lockout, so a reboot restarts it rather than ending it. Lockouts never end before their full
 *   time has really passed.
 *
 * Android-free with an injected clock, like [CrashLoopGuard], so it is unit-tested on the JVM.
 */
class ExitPinGate(
    private val store: PinAttemptStore,
    private val elapsedMs: () -> Long,
) {

    sealed interface Outcome {
        /** Right PIN: exit kiosk. */
        data object Open : Outcome

        /** Wrong PIN; [attemptsLeft] more wrong ones lock the exit. */
        data class Wrong(val attemptsLeft: Int) : Outcome

        /**
         * The exit is locked for another [remainingMs], and the PIN was not checked unless [started]. [started] is true
         * only for the wrong PIN that started this lockout ([failures] in a row), so it is reported once.
         */
        data class Locked(val remainingMs: Long, val failures: Int, val started: Boolean) : Outcome

        /** No on-device exit: this kiosk has no exit PIN, or the attempt couldn't be saved (so it isn't checked). */
        data object Unavailable : Outcome
    }

    /** Whether this kiosk has an on-device exit at all. */
    fun isAvailable(pin: String?): Boolean = !pin.isNullOrBlank()

    /**
     * How long the exit stays locked, or null when it isn't locked. After a reboot (the clock is behind the last wrong
     * PIN) the lockout is saved as starting now.
     */
    fun remainingLockMs(): Long? {
        val failures = store.failures
        val now = elapsedMs()
        return when {
            failures < FREE_ATTEMPTS -> null
            now < store.lastFailureAt -> restartAfterReboot(failures, now)
            else -> (store.lastFailureAt + lockMsFor(failures) - now).takeIf { it > 0L }
        }
    }

    /** Check [entered] against the kiosk's current [pin]. */
    fun attempt(entered: String, pin: String?): Outcome {
        val locked = remainingLockMs()
        return when {
            pin == null || !isAvailable(pin) -> Outcome.Unavailable
            locked != null -> Outcome.Locked(locked, store.failures, started = false)
            else -> countThenCheck(entered, pin)
        }
    }

    private fun countThenCheck(entered: String, pin: String): Outcome {
        // Count first, compare second: nothing that happens after this write can give the attempt back.
        val failures = store.failures + 1
        val counted = store.write(failures = failures, lastFailureAt = elapsedMs())
        return when {
            !counted -> Outcome.Unavailable
            matches(entered, pin) -> {
                store.write(failures = 0, lastFailureAt = 0L)
                Outcome.Open
            }
            failures < FREE_ATTEMPTS -> Outcome.Wrong(attemptsLeft = FREE_ATTEMPTS - failures)
            else -> Outcome.Locked(lockMsFor(failures), failures, started = true)
        }
    }

    /** The clock restarted (a reboot): the lockout starts again now, in full. */
    private fun restartAfterReboot(failures: Int, now: Long): Long {
        store.write(failures = failures, lastFailureAt = now)
        return lockMsFor(failures)
    }

    private fun lockMsFor(failures: Int): Long =
        LOCKOUTS_MS[(failures - FREE_ATTEMPTS).coerceIn(0, LOCKOUTS_MS.lastIndex)]

    companion object {
        /** Wrong PINs in a row before the exit first locks. */
        const val FREE_ATTEMPTS = 3

        private const val SECOND_MS = 1_000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE
        private const val MINUTE_MS = SECONDS_PER_MINUTE * SECOND_MS
        private const val HOUR_MS = SECONDS_PER_HOUR * SECOND_MS

        /** Lockout steps: 30 s, 1 min, 5 min, 15 min, then 1 h for every further wrong PIN. */
        val LOCKOUTS_MS: List<Long> = listOf(30 * SECOND_MS, MINUTE_MS, 5 * MINUTE_MS, 15 * MINUTE_MS, HOUR_MS)

        /** SharedPreferences file name for the persisted attempt count. */
        const val PREFERENCE_NAME = "com.mdmesh.kiosk.pin"

        /**
         * Constant-time comparison, so response timing doesn't reveal how much of a guess was right. Both sides are
         * NFKC-normalised first, so full-width digits from a CJK keyboard match the ASCII digits of the PIN.
         */
        internal fun matches(entered: String, pin: String): Boolean =
            MessageDigest.isEqual(normalized(entered), normalized(pin))

        private fun normalized(s: String): ByteArray =
            Normalizer.normalize(s, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)

        /** A duration for people: "30 s", "4 min", "1 h" (rounded up, so it never says less than is left). */
        fun describe(ms: Long): String {
            val seconds = ceilDiv(ms.coerceAtLeast(0L), SECOND_MS)
            return when {
                seconds < SECONDS_PER_MINUTE -> "$seconds s"
                seconds < SECONDS_PER_HOUR -> "${ceilDiv(seconds, SECONDS_PER_MINUTE)} min"
                else -> "${ceilDiv(seconds, SECONDS_PER_HOUR)} h"
            }
        }

        private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b
    }
}

/**
 * Persistence for [ExitPinGate]: wrong PINs in a row (an attempt counts as wrong until it is proven right), and the
 * [ExitPinGate] clock's reading at the last one. Writes must survive process death; the production store commits
 * synchronously and reports whether the write reached storage.
 */
interface PinAttemptStore {
    val failures: Int
    val lastFailureAt: Long

    /** Persist both fields at once; false when they could not be saved. */
    fun write(failures: Int, lastFailureAt: Long): Boolean
}

/** In-memory [PinAttemptStore] for unit tests; [writable] false simulates a store that can't save. */
class InMemoryPinAttemptStore(var writable: Boolean = true) : PinAttemptStore {
    override var failures: Int = 0
        private set
    override var lastFailureAt: Long = 0L
        private set

    override fun write(failures: Int, lastFailureAt: Long): Boolean {
        if (!writable) return false
        this.failures = failures
        this.lastFailureAt = lastFailureAt
        return true
    }
}
