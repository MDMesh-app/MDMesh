package com.mdmesh.core.command.handlers

import com.mdmesh.policy.UserRestrictions

/**
 * The restrictions a wipe lifts first, and lifting them.
 *
 * Rule: lift a restriction only when it stops a device from formatting its data. AOSP lets a Device
 * Owner wipe past all of them, but some vendor recoveries enforce `DISALLOW_FACTORY_RESET` and refuse
 * to format, leaving the device stuck in recovery (HyperOS, #222). The others the agent sets (the
 * kiosk's `DISALLOW_SAFE_BOOT`, USB, Bluetooth, ...) don't concern formatting, so they stay: a wipe
 * that fails to start leaves the device as locked down as before.
 *
 * Only what our admin holds is cleared and restored, so a restore never adds a block the
 * configuration didn't set. Nothing here is a reason to refuse the wipe, since refusing would stop
 * wiping a lost device: one that stays in force from another source is reported (stock Android wipes
 * past it), and so is a restriction call that throws (what was cleared before it stays off for the wipe).
 */
internal object WipeRestrictions {

    val keys: List<String> = listOf(UserRestrictions.DISALLOW_FACTORY_RESET)

    /**
     * Ours in [lifted] are off now and must be restored if the wipe fails. [setElsewhere] stay in force from another
     * admin or source. [error]: a restriction call threw, so the rest wasn't lifted or checked.
     */
    data class Lift(val lifted: List<String>, val setElsewhere: List<String>, val error: Throwable? = null)

    /** [beforeClear] runs once, before the first restriction is cleared (nothing to clear: never). */
    fun lift(target: WipeTarget, beforeClear: () -> Unit = {}): Lift {
        val lifted = mutableListOf<String>()
        return runCatching { Lift(lifted, liftEach(target, lifted, beforeClear)) }
            .getOrElse { Lift(lifted, emptyList(), it) }
    }

    /** Clears ours among [keys] into [lifted]; returns the keys still in force afterwards. */
    private fun liftEach(target: WipeTarget, lifted: MutableList<String>, beforeClear: () -> Unit): List<String> =
        keys.filter { key ->
            if (target.holdsRestriction(key)) {
                if (lifted.isEmpty()) beforeClear()
                target.clearRestriction(key)
                lifted += key
            }
            target.hasRestriction(key)
        }

    /** Puts [lifted] back; returns the keys that could not be restored. */
    fun restore(target: WipeTarget, lifted: List<String>): List<String> =
        lifted.filter { key -> runCatching { target.addRestriction(key) }.isFailure }
}
