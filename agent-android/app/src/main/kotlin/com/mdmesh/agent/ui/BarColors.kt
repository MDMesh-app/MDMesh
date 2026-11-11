package com.mdmesh.agent.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt
import androidx.core.view.WindowCompat

/**
 * Before Android 15 the status and navigation bars are opaque: they take the screen's [background], with dark icons
 * when it is light (before API 26 the navigation bar can't have dark icons, so on a light background it stays the
 * system's black). From Android 15 this does nothing: the bars are transparent over the screen and [SystemBars.fit]
 * sets their icons (or [updateSystemBarIcons], for a screen whose colours change later).
 */
@Suppress("DEPRECATION") // statusBarColor/navigationBarColor: ignored from API 35, used on API 24-34.
fun Activity.colorSystemBars(@ColorInt background: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val light = SystemBars.isLight(background)
    val bars = WindowCompat.getInsetsController(window, window.decorView)
    window.statusBarColor = background
    bars.isAppearanceLightStatusBars = light
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        window.navigationBarColor = background
        bars.isAppearanceLightNavigationBars = light
    } else {
        window.navigationBarColor = if (light) Color.BLACK else background
    }
}

/**
 * Android 15+: dark or light bar icons for a new [background] after [SystemBars.fit] set them for the first one
 * (the kiosk launcher changes colours with the kiosk). Earlier versions: see [colorSystemBars].
 */
fun Activity.updateSystemBarIcons(@ColorInt background: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val light = SystemBars.isLight(background)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = light
        isAppearanceLightNavigationBars = light
    }
}
