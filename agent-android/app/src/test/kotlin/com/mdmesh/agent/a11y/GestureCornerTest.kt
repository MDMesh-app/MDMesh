package com.mdmesh.agent.a11y

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import com.mdmesh.agent.R
import com.mdmesh.agent.ui.IconSize
import com.mdmesh.agent.ui.KioskExit
import com.mdmesh.agent.ui.KioskPalette
import com.mdmesh.agent.ui.KioskRenderer
import com.mdmesh.agent.ui.KioskScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The hidden corner opens the exit prompt on 7 real taps; nothing in the a11y tree reaches it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-420dpi")
class GestureCornerTest {
    @Test fun `seven taps in the corner open the prompt, accessibility clicks don't`() {
        val a = Robolectric.buildActivity(ComponentActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_MdmAgent) }.setup().get()
        var exits = 0
        val actions = object : KioskRenderer.Actions {
            override fun openApp(packageName: String) = Unit
            override fun openAgain() = Unit
            override fun requestExit() { exits++ }
        }
        val s = A11yScreens()
        KioskRenderer(a, actions).render(
            KioskScreen.Grid(
                KioskPalette.DEFAULT, KioskExit.GESTURE, IconSize.MEDIUM, s.apps.map { it.first }, s.kioskApps(),
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
        val decor = a.window.decorView
        // accessibility: click every node the tree exposes, 7 times
        fun walk(v: View) {
            val actions = v.createAccessibilityNodeInfo().actionList
            val clickable = actions.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            if (v.isImportantForAccessibility && clickable) {
                repeat(7) { v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) }
            }
            if (v is android.view.ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(decor)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("a11y clicks reached the corner", 0, exits)
        // touch: 7 taps 10px in from the top-right corner of the content
        val content = a.findViewById<View>(android.R.id.content)
        val loc = IntArray(2); content.getLocationInWindow(loc)
        val x = (loc[0] + content.width - 10).toFloat(); val y = (loc[1] + 10).toFloat()
        repeat(7) {
            val t = SystemClock.uptimeMillis()
            decor.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            decor.dispatchTouchEvent(MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_UP, x, y, 0))
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(1, exits)
    }
}
