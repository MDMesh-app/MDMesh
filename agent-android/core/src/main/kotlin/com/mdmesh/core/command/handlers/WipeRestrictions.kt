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
 */
internal object WipeRestrictions {

    val keys: List<String> = listOf(UserRestrictions.DISALLOW_FACTORY_RESET)

    sealed interface Lift {
        /** Every restriction in [keys] is off now; [lifted] were on and must be restored if the wipe fails. */
        data class Ready(val lifted: List<String>) : Lift

        /** [key] stays in force after clearing ours (another source sets it); nothing is left lifted. */
        data class StillSet(val key: String) : Lift
    }

    /** Lifts [keys]. On [Lift.StillSet] or a throw, whatever it lifted is already restored. */
    fun lift(target: WipeTarget): Lift {
        val lifted = mutableListOf<String>()
        var outcome: Lift? = null
        try {
            outcome = liftEach(target, lifted)
            return outcome
        } finally {
            if (outcome !is Lift.Ready) restore(target, lifted)
        }
    }

    private fun liftEach(target: WipeTarget, lifted: MutableList<String>): Lift {
        for (key in keys) {
            if (!target.hasRestriction(key)) continue
            target.clearRestriction(key)
            lifted += key
            if (target.hasRestriction(key)) return Lift.StillSet(key)
        }
        return Lift.Ready(lifted)
    }

    /** Puts [lifted] back; returns the keys that could not be restored. */
    fun restore(target: WipeTarget, lifted: List<String>): List<String> =
        lifted.filter { key -> runCatching { target.addRestriction(key) }.isFailure }
}
