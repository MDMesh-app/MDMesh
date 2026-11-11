package com.mdmesh.agent.ui

import android.graphics.drawable.Drawable

/**
 * Everything one kiosk launcher screen shows, as one immutable value. KioskLauncherActivity decides which screen
 * (that is where kiosk behaviour lives); [KioskRenderer] draws it and nothing else touches the launcher's views.
 */
sealed interface KioskScreen {
    val palette: KioskPalette
    val exit: KioskExit

    /** No kiosk: the wordmark and "Managed device", in the default colours. */
    data object Idle : KioskScreen {
        override val palette: KioskPalette = KioskPalette.DEFAULT
        override val exit: KioskExit = KioskExit.NONE
    }

    /** Single-app kiosk, behind the pinned app while it opens; [appName] once known. */
    data class Splash(
        override val palette: KioskPalette,
        override val exit: KioskExit,
        val packageName: String,
        val appName: String?,
    ) : KioskScreen

    /** The app grid for [packages] (the allowlist); [apps] is null until they are loaded, then the ones installed. */
    data class Grid(
        override val palette: KioskPalette,
        override val exit: KioskExit,
        val iconSize: IconSize,
        val packages: List<String>,
        val apps: List<KioskApp>?,
    ) : KioskScreen

    /** The pinned app closed too often: "App closed" and Open again. */
    data class Hold(override val palette: KioskPalette, override val exit: KioskExit) : KioskScreen
}

/** One installed app in the grid. [icon] comes from [KioskAppLoader]'s cache, the same instance while unchanged. */
data class KioskApp(val packageName: String, val label: String, val icon: Drawable)

/** The on-device way out a screen offers. */
enum class KioskExit {
    /** No exit PIN, or exit mode "remote": the kiosk ends only from the console. */
    NONE,

    /** An "Exit kiosk" button at the bottom. */
    VISIBLE,

    /** An invisible top-right corner: 7 taps within 3 seconds. */
    GESTURE,
    ;

    companion object {
        /** [exitMode] from the kiosk; [pinAvailable] is ExitPinGate.isAvailable for its PIN. */
        fun of(exitMode: String?, pinAvailable: Boolean): KioskExit = when {
            !pinAvailable -> NONE
            exitMode == "visible" -> VISIBLE
            exitMode == "gesture" -> GESTURE
            else -> NONE
        }
    }
}

/** Grid icon size from the kiosk theme; anything unknown is small, as before. */
enum class IconSize(val dp: Int) {
    SMALL(SMALL_ICON_DP),
    MEDIUM(MEDIUM_ICON_DP),
    LARGE(LARGE_ICON_DP),
    ;

    companion object {
        fun of(value: String?): IconSize = when (value?.uppercase()) {
            "LARGE" -> LARGE
            "MEDIUM" -> MEDIUM
            else -> SMALL
        }
    }
}

private const val SMALL_ICON_DP = 56
private const val MEDIUM_ICON_DP = 72
private const val LARGE_ICON_DP = 96
