package com.mdmesh.agent.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt
import androidx.core.view.WindowCompat

/**
 * The system bars for a screen in [background], for screens whose colours change after [SystemBars.fit] (the kiosk
 * launcher) and for Android 7-14. Before Android 15 the bars are opaque: they take [background], with dark icons when
 * it is light (before API 26 the navigation bar can't have dark icons, so on a light background it stays the
 * system's black). From Android 15 the bars are transparent over the screen and only the icon colour is set.
 * Insets stay with [SystemBars.fit].
 */
@Suppress("DEPRECATION") // statusBarColor/navigationBarColor: ignored from API 35, used on API 24-34.
fun Activity.colorSystemBars(@ColorInt background: Int) {
    val light = SystemBars.isLight(background)
    val bars = WindowCompat.getInsetsController(window, window.decorView)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        bars.isAppearanceLightStatusBars = light
        bars.isAppearanceLightNavigationBars = light
        return
    }
    window.statusBarColor = background
    bars.isAppearanceLightStatusBars = light
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        window.navigationBarColor = background
        bars.isAppearanceLightNavigationBars = light
    } else {
        window.navigationBarColor = if (light) Color.BLACK else background
    }
}
