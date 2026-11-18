package com.mdmesh.kiosk

import java.security.MessageDigest
import java.text.Normalizer

/**
 * The kiosk's on-device exit: whether there is one, and whether an entered PIN opens it.
 *
 * - **No PIN, no exit.** A kiosk without an exit PIN has no on-device exit at all; it ends only from the console.
 * - **Wrong PINs lock the exit, in rounds, then one at a time.** The first [FIRST_ROUND_TRIES] wrong PINs in a row
 *   are free (room for typos); the last of them locks the exit for the first of [LOCKOUTS_MS]. Each lockout then
 *   grants [ROUND_TRIES] more tries, the last of which locks it again for the next, longer step. Once a lockout has
 *   reached the last step (1 h), every further wrong PIN locks it again for that long: one guess an hour, as before
 *   0.5.0. While it is locked no PIN is checked, right or wrong. A right PIN clears the count. At one guess an hour a
 *   6-digit PIN (the console's rule) takes about 57 years on average to guess (10^6 / 2 guesses at 24 a day); a 4-digit
 *   PIN from before the rule, about 7 months.
 * - **The count belongs to a PIN.** It is kept with a fingerprint of the PIN it was counted against ([keyOf]): when
 *   the kiosk's PIN changes (a new configuration, a custom kiosk) the count starts again. An administrator can also
 *   clear it from the console ([clear]).
 * - **Counted before it is checked.** Every attempt is saved before the PIN is compared, so ending the app mid-check
 *   or a store that can't write never hands out a free guess (the attempt is refused instead).
 * - **Restarts and clocks don't help.** Lockouts are timed on [elapsedMs], a clock that only moves forward and can't
 *   be set (Android's `elapsedRealtime`). It starts again from zero at a reboot, which the gate notices and treats as
 *   the start of the lockout, so a reboot restarts it rather than ending it. Lockouts never end before their full
 *   time has really passed.
 * - **Updates keep the lockout.** A count saved by an older agent (one lockout per wrong PIN after the third) is read
 *   as the round whose lockout is at least as long ([migrate]).
 * - **One at a time.** Every read-and-write is synchronized, so a console clear arriving on the check-in thread while
 *   a PIN is being checked on the main thread is never lost or half-applied.
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

    /** What [clear] cleared: [failures] wrong PINs in a row, and whether the exit was locked then. */
    data class Cleared(val failures: Int, val wasLocked: Boolean)

    /** Whether this kiosk has an on-device exit at all. */
    fun isAvailable(pin: String?): Boolean = !pin.isNullOrBlank()

    /**
     * How long the exit for [pin] stays locked, or null when it isn't locked (or there is no exit). After a reboot
     * (the clock is behind the last wrong PIN) the lockout is saved as starting now.
     */
    @Synchronized
    fun remainingLockMs(pin: String?): Long? = pin?.takeIf(::isAvailable)?.let { remaining(load(it)) }

    /**
     * How many more wrong PINs the exit for [pin] takes before it locks; null when there's nothing to warn about: no
     * wrong PIN yet, or locked now (then Exit locked says it). Read from the store, so a rebuilt prompt says it again.
     */
    @Synchronized
    fun attemptsLeft(pin: String?): Int? {
        val state = pin?.takeIf(::isAvailable)?.let(::load) ?: return null
        return if (state.failures == 0 || remaining(state) != null) null else triesBeforeLock(state.failures)
    }

    /** Check [entered] against the kiosk's current [pin]. */
    @Synchronized
    fun attempt(entered: String, pin: String?): Outcome {
        if (pin == null || !isAvailable(pin)) return Outcome.Unavailable
        val state = load(pin)
        val locked = remaining(state)
        return if (locked != null) {
            Outcome.Locked(locked, state.failures, started = false)
        } else {
            countThenCheck(entered, pin, state)
        }
    }

    /**
     * Clears the count and any lockout (the console's Clear PIN lockout). Returns what it cleared, or null when the
     * clear could not be saved (the count and any lockout then stay as they were).
     */
    @Synchronized
    fun clear(): Cleared? {
        val state = migrate(store.read())
        if (state.failures <= 0) return Cleared(failures = 0, wasLocked = false)
        val locked = remaining(state) != null
        return if (store.write(PinAttempts())) Cleared(state.failures, locked) else null
    }

    private fun countThenCheck(entered: String, pin: String, state: PinAttempts): Outcome {
        // Count first, compare second: nothing that happens after this write can give the attempt back.
        val failures = state.failures + 1
        val counted = store.write(PinAttempts(failures, elapsedMs(), keyOf(pin)))
        return when {
            !counted -> Outcome.Unavailable
            matches(entered, pin) -> {
                store.write(PinAttempts())
                Outcome.Open
            }
            locksAt(failures) -> Outcome.Locked(lockMsFor(failures), failures, started = true)
            else -> Outcome.Wrong(attemptsLeft = triesBeforeLock(failures))
        }
    }

    /**
     * The saved count as it applies to [pin]: an older agent's count read in today's rounds, and none at all when it
     * was counted against another PIN (then it is cleared in the store too, best effort: the PIN changed either way).
     */
    private fun load(pin: String): PinAttempts {
        val state = migrate(store.read())
        if (state.failures == 0 || state.pinKey == null || state.pinKey == keyOf(pin)) return state
        store.write(PinAttempts())
        return PinAttempts()
    }

    /** How long [state] keeps the exit locked, or null; restarts the lockout after a reboot. */
    private fun remaining(state: PinAttempts): Long? {
        if (!locksAt(state.failures)) return null
        val now = elapsedMs()
        return if (now < state.lastFailureAt) {
            // The clock restarted (a reboot): the lockout starts again now, in full.
            store.write(state.copy(lastFailureAt = now))
            lockMsFor(state.failures)
        } else {
            (state.lastFailureAt + lockMsFor(state.failures) - now).takeIf { it > 0L }
        }
    }

    companion object {
        /** Wrong PINs in a row before the exit first locks: room for a few typos. */
        const val FIRST_ROUND_TRIES = 5

        /** Wrong PINs after each lockout before the exit locks again. */
        const val ROUND_TRIES = 3

        private const val SECOND_MS = 1_000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE
        private const val MINUTE_MS = SECONDS_PER_MINUTE * SECOND_MS
        private const val HOUR_MS = SECONDS_PER_HOUR * SECOND_MS

        /** Lockout per round: 1 min, 5 min, 15 min, then 1 h for every further wrong PIN. */
        val LOCKOUTS_MS: List<Long> = listOf(MINUTE_MS, 5 * MINUTE_MS, 15 * MINUTE_MS, HOUR_MS)

        /** SharedPreferences file name for the persisted attempt count. */
        const val PREFERENCE_NAME = "com.mdmesh.kiosk.pin"

        /** The wrong PIN that first locks the exit for the last step; from then on every wrong PIN locks it. */
        private val lastStepAt: Int get() = lockingCount(LOCKOUTS_MS.lastIndex)

        /**
         * Whether the [failures]th wrong PIN in a row locks the exit: the end of each round, and every wrong PIN once
         * the last step has been reached.
         */
        internal fun locksAt(failures: Int): Boolean = when {
            failures < FIRST_ROUND_TRIES -> false
            failures >= lastStepAt -> true
            else -> (failures - FIRST_ROUND_TRIES) % ROUND_TRIES == 0
        }

        /** Wrong PINs left before the exit locks, after [failures] in a row (a full round right after a lockout). */
        internal fun triesBeforeLock(failures: Int): Int = when {
            failures < FIRST_ROUND_TRIES -> FIRST_ROUND_TRIES - failures
            failures >= lastStepAt -> 1
            else -> ROUND_TRIES - (failures - FIRST_ROUND_TRIES) % ROUND_TRIES
        }

        internal fun lockMsFor(failures: Int): Long =
            if (failures >= lastStepAt) {
                LOCKOUTS_MS.last()
            } else {
                LOCKOUTS_MS[((failures - FIRST_ROUND_TRIES) / ROUND_TRIES).coerceIn(0, LOCKOUTS_MS.lastIndex)]
            }

        /** The wrong-PIN count that ends lockout round [step] (0 = the first lockout). */
        private fun lockingCount(step: Int): Int = FIRST_ROUND_TRIES + step * ROUND_TRIES

        /**
         * A count saved by an agent before [PinAttempts.SCHEMA] 2, in today's rounds. Those agents locked at the third
         * wrong PIN and then at every wrong PIN, for 30 s, 1 min, 5 min, 15 min, then 1 h. A count that was locked
         * becomes the round whose lockout is at least as long, timed from the same wrong PIN, so an update never ends
         * a lockout early; one that wasn't locked keeps its count (and gets the extra free tries).
         */
        internal fun migrate(state: PinAttempts): PinAttempts {
            if (state.schema >= PinAttempts.SCHEMA) return state
            val f = state.failures.coerceAtLeast(0)
            val failures = if (f < LEGACY_FREE_ATTEMPTS) {
                f
            } else {
                val step = (f - LEGACY_FREE_ATTEMPTS).coerceAtMost(LEGACY_STEP_TO_ROUND.lastIndex)
                lockingCount(LEGACY_STEP_TO_ROUND[step])
            }
            return state.copy(failures = failures, schema = PinAttempts.SCHEMA)
        }

        private const val LEGACY_FREE_ATTEMPTS = 3

        /** The old lockout steps (30 s, 1 min, 5 min, 15 min, 1 h) as the round with a lockout at least as long. */
        private val LEGACY_STEP_TO_ROUND = listOf(0, 0, 1, 2, 3)

        /**
         * Which PIN a count was counted against: a SHA-256 of the normalized PIN, so the store can tell a changed PIN.
         * Not a secret worth more than the PIN itself, which the kiosk's own state already keeps in the same
         * app-private storage.
         */
        fun keyOf(pin: String): String = MessageDigest.getInstance("SHA-256")
            .digest(("mdmesh.exitPin:" + Normalizer.normalize(pin, Normalizer.Form.NFKC)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
 * What [PinAttemptStore] keeps: wrong PINs in a row (an attempt counts as wrong until it is proven right), the
 * [ExitPinGate] clock's reading at the last one, and which PIN they were counted against ([ExitPinGate.keyOf]; null
 * when an agent before [SCHEMA] 2 saved it, which counts against any PIN).
 */
data class PinAttempts(
    val failures: Int = 0,
    val lastFailureAt: Long = 0L,
    val pinKey: String? = null,
    val schema: Int = SCHEMA,
) {
    companion object {
        /** 2 (agent 0.5.0): rounds, and the PIN fingerprint. 1: a lockout per wrong PIN after the third. */
        const val SCHEMA = 2
    }
}

/**
 * Persistence for [ExitPinGate]. Writes must survive process death; the production store commits synchronously and
 * reports whether the write reached storage.
 */
interface PinAttemptStore {
    fun read(): PinAttempts

    /** Persist every field at once; false when they could not be saved. */
    fun write(attempts: PinAttempts): Boolean
}

/** In-memory [PinAttemptStore] for unit tests; [writable] false simulates a store that can't save. */
class InMemoryPinAttemptStore(var writable: Boolean = true) : PinAttemptStore {
    var attempts: PinAttempts = PinAttempts()

    val failures: Int get() = attempts.failures

    override fun read(): PinAttempts = attempts

    override fun write(attempts: PinAttempts): Boolean {
        if (!writable) return false
        this.attempts = attempts
        return true
    }
}
