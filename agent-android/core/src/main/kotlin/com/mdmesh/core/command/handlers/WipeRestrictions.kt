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
 * configuration didn't set. One that stays in force from another source refuses the wipe.
 */
internal object WipeRestrictions {

    val keys: List<String> = listOf(UserRestrictions.DISALLOW_FACTORY_RESET)

    sealed interface Lift {
        /** Every restriction in [keys] is off now; [lifted] were ours and must be restored if the wipe fails. */
        data class Ready(val lifted: List<String>) : Lift

        /** [key] stays in force from another admin or source. Ours are restored, except [notRestored]. */
        data class StillSet(val key: String, val notRestored: List<String>) : Lift

        /** A restriction call threw. Ours are restored, except [notRestored]. */
        data class Error(val cause: Throwable, val notRestored: List<String>) : Lift
    }

    /** [beforeClear] runs once, before the first restriction is cleared (nothing to clear: never). */
    fun lift(target: WipeTarget, beforeClear: () -> Unit = {}): Lift {
        val lifted = mutableListOf<String>()
        val stillSet = runCatching { liftEach(target, lifted, beforeClear) }
            .getOrElse { return Lift.Error(it, restore(target, lifted)) }
        return if (stillSet == null) Lift.Ready(lifted) else Lift.StillSet(stillSet, restore(target, lifted))
    }

    /** Clears ours among [keys] into [lifted]; returns the first key still in force afterwards, if any. */
    private fun liftEach(target: WipeTarget, lifted: MutableList<String>, beforeClear: () -> Unit): String? {
        for (key in keys) {
            if (target.holdsRestriction(key)) {
                if (lifted.isEmpty()) beforeClear()
                target.clearRestriction(key)
                lifted += key
            }
            if (target.hasRestriction(key)) return key
        }
        return null
    }

    /** Puts [lifted] back; returns the keys that could not be restored. */
    fun restore(target: WipeTarget, lifted: List<String>): List<String> =
        lifted.filter { key -> runCatching { target.addRestriction(key) }.isFailure }
}
