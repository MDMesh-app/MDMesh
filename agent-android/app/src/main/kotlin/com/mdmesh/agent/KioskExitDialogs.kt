package com.mdmesh.agent

import com.mdmesh.kiosk.KioskExit
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskThemeDto

/** What the kiosk launcher shows after it applies a kiosk state. */
internal enum class LauncherScreen {
    /** No kiosk: the idle screen, no exit. */
    IDLE,

    /** The app grid (or its fallback when the pinned app can't be launched), with the exit affordance. */
    GRID,

    /** The hold screen after a crash loop, with the exit affordance. */
    HOLD,

    /**
     * The pinned app was launched on top, over the splash. The splash does carry the exit affordance (reachable if
     * the app fails to start or closes at once), but a dialog restored here would sit hidden behind the app that a
     * recreation just relaunched, so it's dropped; staff can open it again from the splash.
     */
    PINNED_APP,
}

/**
 * The launcher's exit dialogs, as saved across a recreation (DialogSlot tags), and whether one comes back. It comes
 * back only on a screen that has the exit on it; otherwise it's dropped, so it can't pop up later on its own.
 */
internal object KioskExitDialogs {
    const val PIN = "pin"
    const val LOCKED = "locked"

    /** "Exit isn't available": the right PIN, but the exit couldn't start (its journal entry couldn't be written). */
    const val UNAVAILABLE = "unavailable"

    /**
     * The PIN that opens [kiosk]'s on-device exit, or null when it has none: no kiosk, no PIN, or exit mode "remote"
     * ([KioskExit.onDevice]). The one answer for the affordance, the prompt and the PIN check, read at each use.
     */
    fun onDevicePin(kiosk: KioskApplyPayload?): String? = kiosk?.takeIf(KioskExit::onDevice)?.password

    /**
     * The kiosk an "Exit isn't available" belongs to, as kept in saved state so a recreation reopens it only on that
     * kiosk: what [com.mdmesh.proto.sameKioskAs] compares, less the look and the exit PIN. The PIN (or anything
     * derived from it) is never written to saved state, so a kiosk that differs only in its PIN counts as the same.
     */
    fun identityOf(kiosk: KioskApplyPayload): String = kiosk.copy(password = null, theme = KioskThemeDto()).toString()

    /** The dialog to reopen on [screen], given the [tag] that was open before the recreation; null for none. */
    fun reopenOn(screen: LauncherScreen, tag: String?): String? = when {
        tag != PIN && tag != LOCKED && tag != UNAVAILABLE -> null
        screen == LauncherScreen.GRID || screen == LauncherScreen.HOLD -> tag
        else -> null
    }
}
