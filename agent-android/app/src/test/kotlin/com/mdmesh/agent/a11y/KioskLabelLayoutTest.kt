package com.mdmesh.agent.a11y

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.mdmesh.agent.R
import com.mdmesh.agent.ui.AppGridLayout
import com.mdmesh.agent.ui.IconSize
import com.mdmesh.agent.ui.KioskApp
import com.mdmesh.agent.ui.KioskExit
import com.mdmesh.agent.ui.KioskPalette
import com.mdmesh.agent.ui.KioskScreen
import com.mdmesh.agent.ui.LabelLines
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On the real launcher: labels break between words only (3.3), and at the end of the scroll the Exit button covers
 * no label, in portrait and landscape, at font scale 1 and 2.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class KioskLabelLayoutTest(private val fontScale: Float) {
    private val s = A11yScreens()

    @After fun close() = s.close()

    private val labels = listOf(
        "Inventory", "Timesheets", "Maintenance Requests", "Shift Swap", "Supercalifragilistic Expialidocious",
        "Point-of-Sale", "Safety Checklists and Forms", "Price Check", "Orders", "Deliveries", "Returns", "Training",
        "Feedback", "Stock Count", "Warehouse Order Picking", "Time Clock", "Team Chat", "Manuals", "Scanner",
        "Customer Accounts", "Loyalty", "Gift Cards", "Reports", "Settings",
    )

    private fun screen(): KioskScreen.Grid {
        val apps = labels.mapIndexed { i, l -> KioskApp("com.example.a$i", l, ColorDrawable(Color.GRAY)) }
        val packages = apps.map { it.packageName }
        return KioskScreen.Grid(KioskPalette.DEFAULT, KioskExit.VISIBLE, IconSize.MEDIUM, packages, apps)
    }

    private fun <T : View> all(v: View, type: Class<T>): List<T> = when {
        type.isInstance(v) -> listOf(type.cast(v)!!)
        v is ViewGroup -> (0 until v.childCount).flatMap { all(v.getChildAt(it), type) }
        else -> emptyList()
    }

    private fun appLabels(root: View): List<TextView> =
        all(root, AppGridLayout::class.java).flatMap { g ->
            (0 until g.childCount).map { (g.getChildAt(it) as ViewGroup).getChildAt(1) as TextView }
        }

    @Test fun labelsBreakBetweenWordsOnly() {
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
        val root = s.kioskScreen(screen()).window.decorView
        for (label in appLabels(root)) {
            val layout = label.layout ?: continue
            val whole = (label as com.mdmesh.agent.ui.LabelView).label.toString()
            val words = LabelLines.normalize(whole).split(' ').toSet()
            for (i in 0 until layout.lineCount) {
                val line = label.text.subSequence(layout.getLineStart(i), layout.getLineEnd(i)).toString().trim()
                if (line.endsWith(LabelLines.ELLIPSIS) || line.isEmpty()) continue
                // Whole words, or pieces a word may break into (after - or /).
                val ok = line.split(' ').all { w ->
                    w in words || words.any { it.contains(w) && (w.endsWith("-") || w.endsWith("/") || it.endsWith(w)) }
                }
                assertTrue("'$whole' line $i '$line' is whole words", ok)
            }
            assertTrue("'$whole': at most ${label.maxLines} lines", layout.lineCount <= label.maxLines)
        }
    }

    @Test fun atTheEndOfTheScrollTheExitButtonCoversNoLabel() {
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
        val root = s.kioskScreen(screen()).window.decorView
        val scroll = all(root, ScrollView::class.java).single()
        scroll.fullScroll(View.FOCUS_DOWN)
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        s.idle()
        root.requestLayout()
        s.idle()
        val exitLabel = root.context.getString(R.string.kiosk_exit_button)
        val exit = all(root, Button::class.java).single { it.text == exitLabel }
        val exitTop = IntArray(2).also(exit::getLocationInWindow)[1]
        // The room the grid leaves (3.3): the button as laid out, plus its 24dp margin, plus 8dp.
        val density = root.resources.displayMetrics.density
        val column = scroll.getChildAt(0)
        val room = exit.height + Math.round(24 * density) + Math.round(8 * density)
        assertTrue("room ${column.paddingBottom} for an Exit ${exit.height} high", column.paddingBottom == room)
        for (label in appLabels(root)) {
            val at = IntArray(2).also(label::getLocationInWindow)
            if (at[1] + label.height <= 0) continue // scrolled away above
            val bottom = at[1] + label.height
            assertTrue("'${label.text}' ends at $bottom, Exit starts at $exitTop", bottom <= exitTop)
        }
    }
}

@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-420dpi")
class KioskLabelLayoutPortraitTest : KioskLabelLayoutTest(1f)

@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-notnight-420dpi")
class KioskLabelLayoutLandscapeTest : KioskLabelLayoutTest(1f)

@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-420dpi")
class KioskLabelLayoutLargeFontTest : KioskLabelLayoutTest(2f)

@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-notnight-420dpi")
class KioskLabelLayoutLandscapeLargeFontTest : KioskLabelLayoutTest(2f)

@Config(sdk = [26], qualifiers = "w360dp-h640dp-port-notnight-xhdpi")
class KioskLabelLayout26Test : KioskLabelLayoutTest(1f)
