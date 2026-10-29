package com.mdmesh.kiosk

/**
 * What kiosk HOME does when it comes back to the front.
 *
 * In single-app kiosk the pinned app covers HOME, so HOME showing again means the app closed: the user pressed Back
 * or Home, or the app crashed. Each return counts towards [CrashLoopGuard]. Once the guard trips, HOME stops
 * reopening the app and holds the device on its own screen until someone opens the app again or a different kiosk
 * is applied.
 *
 * Kiosk itself stays on either way. Only the configured exit (the admin PIN) or the server ends it, so closing the
 * pinned app over and over can never take the device out of kiosk. Holding instead of reopening is what keeps a
 * crashing app from looping.
 */
class KioskReturnPolicy(private val guard: CrashLoopGuard) {

    enum class Action { REOPEN, HOLD }

    /** True while HOME holds instead of reopening the pinned app. */
    var held: Boolean = false
        private set

    /** HOME came back to the front in single-app kiosk. */
    fun onReturned(): Action {
        if (held) return Action.HOLD
        guard.registerFault()
        return check()
    }

    /**
     * A kiosk state was applied: at start (including after a reboot, and each time the launcher comes back to the
     * foreground) or when the configuration changed. A hold in progress continues, and a loop that tripped less than
     * [CrashLoopGuard.LOOP_TIME_SPAN] ago still holds, so a reboot doesn't restart it. A different kiosk ([changed])
     * starts afresh: the app that kept closing may not be in it.
     */
    fun onApplied(changed: Boolean): Action {
        if (changed) retry()
        return if (held) Action.HOLD else check()
    }

    /** Someone chose to open the app again: start counting afresh. */
    fun retry() {
        guard.reset()
        held = false
    }

    private fun check(): Action {
        held = guard.isCrashLoopDetected()
        return if (held) Action.HOLD else Action.REOPEN
    }
}
