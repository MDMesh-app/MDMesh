package com.mdmesh.agent

/**
 * Makes the kiosk HOME act once each time it comes back to the front, whatever the order of `onResume` and the saved
 * kiosk that the restarted collector delivers. DataStore delivers after `onResume`; a store that delivers at once
 * would come before it. Either way:
 * - the same single-app kiosk again: the return is handled once ([Step.RETURN]: count it, reopen the app or hold);
 * - a different kiosk, applied while HOME was behind the app: only the new one is applied ([Step.APPLY]); the old
 *   pinned app isn't reopened first;
 * - resumed without having been stopped (something translucent on top went away): no delivery comes, so the return
 *   is handled at once, as before.
 */
class HomeResumeGate {

    enum class Step {
        /** Nothing to do now. */
        NONE,

        /** Resumed; the delivery that follows a restart decides. */
        WAIT,

        /** Back at the front with the same kiosk: handle the return. */
        RETURN,

        /** Apply the delivered kiosk. */
        APPLY,

        /** No kiosk: idle. */
        IDLE,
    }

    /** Started again: the collector restarts and will deliver the saved kiosk. */
    private var awaiting = false

    /** Resumed while [awaiting]. */
    private var resumedEarly = false

    /** The delivery came first and already acted: the coming `onResume` must not act again. */
    private var skipResume = false

    /** Call before `super.onStart()`, so it runs before the collector restarts. */
    fun onStart() {
        awaiting = true
        resumedEarly = false
        skipResume = false
    }

    fun onResume(): Step = when {
        awaiting -> Step.WAIT.also { resumedEarly = true }
        skipResume -> Step.NONE.also { skipResume = false }
        else -> Step.RETURN
    }

    fun onDelivery(delivery: KioskDelivery): Step {
        if (!awaiting) return stepOf(delivery)
        awaiting = false
        val resumed = resumedEarly
        resumedEarly = false
        return when (delivery) {
            KioskDelivery.PIN_ONLY -> if (resumed) Step.RETURN else Step.NONE
            KioskDelivery.APPLY, KioskDelivery.IDLE -> stepOf(delivery).also { skipResume = !resumed }
        }
    }

    private fun stepOf(delivery: KioskDelivery): Step = when (delivery) {
        KioskDelivery.APPLY -> Step.APPLY
        KioskDelivery.IDLE -> Step.IDLE
        KioskDelivery.PIN_ONLY -> Step.NONE
    }
}
