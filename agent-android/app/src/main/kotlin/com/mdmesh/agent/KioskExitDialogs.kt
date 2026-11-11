package com.mdmesh.agent

/** What the kiosk launcher shows after it applies a kiosk state. */
internal enum class LauncherScreen {
    /** No kiosk: the idle screen, no exit. */
    IDLE,

    /** The app grid (or its fallback when the pinned app can't be launched), with the exit affordance. */
    GRID,

    /** The hold screen after a crash loop, with the exit affordance. */
    HOLD,

    /** The pinned app was launched on top; the launcher behind it only shows a splash. */
    PINNED_APP,
}

/**
 * The launcher's exit dialogs, as saved across a recreation (DialogSlot tags), and whether one comes back. It comes
 * back only on a screen that has the exit on it; otherwise it's dropped, so it can't pop up later on its own.
 */
internal object KioskExitDialogs {
    const val PIN = "pin"
    const val LOCKED = "locked"

    /** The dialog to reopen on [screen], given the [tag] that was open before the recreation; null for none. */
    fun reopenOn(screen: LauncherScreen, tag: String?): String? = when {
        tag != PIN && tag != LOCKED -> null
        screen == LauncherScreen.GRID || screen == LauncherScreen.HOLD -> tag
        else -> null
    }
}
