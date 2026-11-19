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
 * configuration didn't set. One that stays in force from another source is reported, not a reason to
 * refuse: stock Android wipes past it, and refusing would stop wiping a lost device.
 */
internal object WipeRestrictions {

    val keys: List<String> = listOf(UserRestrictions.DISALLOW_FACTORY_RESET)

    sealed interface Lift {
        /**
         * Ours are off now; [lifted] must be restored if the wipe fails. [setElsewhere] stay in force from another
         * admin or source.
         */
        data class Ready(val lifted: List<String>, val setElsewhere: List<String>) : Lift

        /** A restriction call threw. Ours are restored, except [notRestored]. */
        data class Error(val cause: Throwable, val notRestored: List<String>) : Lift
    }

    /** [beforeClear] runs once, before the first restriction is cleared (nothing to clear: never). */
    fun lift(target: WipeTarget, beforeClear: () -> Unit = {}): Lift {
        val lifted = mutableListOf<String>()
        return runCatching { Lift.Ready(lifted, liftEach(target, lifted, beforeClear)) }
            .getOrElse { Lift.Error(it, restore(target, lifted)) }
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
