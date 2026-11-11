package com.mdmesh.agent.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps a screen clear of the status bar, navigation bar and display cutout. The agent targets API 35, so on
 * Android 15+ every activity is drawn edge to edge, under transparent system bars; earlier versions lay the content
 * out between opaque bars and pass no bar insets down, so the same code is a no-op there.
 */
object SystemBars {

    /**
     * Pads [root] (on top of its own padding) by the system bar and cutout insets, so its background runs under the
     * bars and its content stays clear of them. On Android 15+ the bar icons are also set dark or light to read on
     * [background], the colour behind them.
     */
    fun fit(activity: Activity, root: View, @ColorInt background: Int) {
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        // Before 15 the bars are opaque and coloured by the theme; their icon colour follows that, not [background].
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val light = isLight(background)
            WindowCompat.getInsetsController(activity.window, root).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    /** Whether dark bar icons read better on [background] than light ones. */
    internal fun isLight(@ColorInt background: Int): Boolean =
        Contrast.ratio(background, Color.BLACK) > Contrast.ratio(background, Color.WHITE)
}
