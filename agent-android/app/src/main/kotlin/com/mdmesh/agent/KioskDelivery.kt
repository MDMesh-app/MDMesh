package com.mdmesh.agent

import com.mdmesh.proto.KioskApplyPayload

/**
 * What the kiosk launcher does when the saved kiosk reaches it from [com.mdmesh.core.store.KioskStateStore]. The
 * launcher collects the store only while started, so each time it comes back to the front it gets the current
 * kiosk again ([previous] == [next]) even though nothing changed.
 */
enum class KioskDelivery {
    /** No kiosk: leave lock task, show the idle screen. */
    IDLE,

    /** Pin, check the crash-loop hold, show the kiosk (open the pinned app or draw the grid). */
    APPLY,

    /**
     * The single-app kiosk again, only because the launcher came back to the front: keep lock task and leave the
     * rest to `onResume`, which counts the return and reopens the app or holds. Applying it here as well opened
     * the pinned app a second time on every return to HOME.
     */
    PIN_ONLY,
    ;

    companion object {
        fun of(previous: KioskApplyPayload?, next: KioskApplyPayload?): KioskDelivery = when {
            next == null -> IDLE
            next == previous && next.mode == "single" && next.pinPackage != null -> PIN_ONLY
            else -> APPLY
        }
    }
}
