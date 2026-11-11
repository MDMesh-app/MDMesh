package com.mdmesh.agent.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt
import androidx.core.view.WindowCompat

/**
 * Before Android 15 the status and navigation bars are opaque: give them the screen's own [background], with dark
 * icons when that is light. [SystemBars.fit] does the Android 15+ part (transparent bars, insets, icon colour), so
 * this does nothing there. Before API 26 the navigation bar can't have dark icons, so on a light background it stays
 * the system's black.
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
