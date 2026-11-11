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
class KioskReturnPolicy(
    private val guard: CrashLoopGuard,
    /** Where the hold is kept, so a recreated launcher (rotation, locale, process restore) still holds. */
    private val holds: HoldStore = InMemoryHoldStore(),
    /** Which boot this is (Settings.Global.BOOT_COUNT in production): a hold belongs to the boot it started in. */
    private val boot: () -> Long = { 0L },
) {

    enum class Action { REOPEN, HOLD }

    /** True while HOME holds instead of reopening the pinned app. Saved, not derived from the guard's window. */
    val held: Boolean
        get() = holds.heldBoot == boot()

    /** HOME came back to the front in single-app kiosk. */
    fun onReturned(): Action {
        if (held) return Action.HOLD
        guard.registerFault()
        return check()
    }

    /**
     * A kiosk state was applied: at start (including after a reboot, and each time the launcher comes back to the
     * foreground) or when the configuration changed. A hold in progress continues, however long ago it started and
     * whether or not the launcher was recreated meanwhile; after a reboot, a loop that tripped less than
     * [CrashLoopGuard.LOOP_TIME_SPAN] ago still holds. A different kiosk ([changed]) starts afresh: the app that kept
     * closing may not be in it.
     */
    fun onApplied(changed: Boolean): Action {
        if (changed) retry()
        return if (held) Action.HOLD else check()
    }

    /** Someone chose to open the app again, or the kiosk ended: start counting afresh. */
    fun retry() {
        guard.reset()
        holds.writeHeld(null)
    }

    private fun check(): Action {
        if (!guard.isCrashLoopDetected()) return Action.REOPEN
        holds.writeHeld(boot())
        return Action.HOLD
    }
}
