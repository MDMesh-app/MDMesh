package com.mdmesh.agent.a11y

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.mdmesh.agent.R
import com.mdmesh.agent.ui.AppGridLayout
import com.mdmesh.agent.ui.IconSize
import com.mdmesh.agent.ui.KioskExit
import com.mdmesh.agent.ui.KioskPalette
import com.mdmesh.agent.ui.KioskScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What TalkBack gets from the agent's screens: headings, one stop per fact (label with value), status changes
 * announced (live regions), roles, pane titles, and nothing hidden that should be found (or found that is meant to
 * stay hidden).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-420dpi")
class SemanticsTest {

    private val s = A11yScreens()

    @Before fun setUp() = s.seed()

    @After fun tearDown() = s.close()

    private fun str(id: Int, vararg args: Any): String = s.app.getString(id, *args)

    private fun heading(v: View): Boolean = v.createAccessibilityNodeInfo().isHeading

    private fun text(root: View, value: String): TextView =
        checkNotNull(s.find(root, TextView::class.java) { it.text.toString() == value }) { "no '$value'" }

    // ---- status screen ----

    @Test fun `the status screen has headings`() {
        val root = s.status(s.kiosk, s.statusState(configured = true)).window.decorView
        assertTrue("wordmark", heading(root.findViewById(R.id.status_wordmark_text)))
        assertTrue("Kiosk card title", heading(root.findViewById(R.id.status_kiosk_title)))
    }

    @Test fun `each fact is one stop, its label with its value`() {
        val root = s.status(s.kiosk, s.statusState(configured = true)).window.decorView
        val id = root.findViewById<View>(R.id.status_device_id_group)
        assertEquals("${str(R.string.status_label_device_id)}, dev_7f3a9c21e4b84d0e", id.contentDescription)
        val server = root.findViewById<View>(R.id.status_server_group)
        assertEquals("${str(R.string.status_label_server)}, https://mdm.example.com/", server.contentDescription)
        val version = root.findViewById<View>(R.id.status_row_version)
        assertTrue(version.contentDescription.startsWith("${str(R.string.status_label_agent_version)}, "))
        for (hidden in listOf(R.id.status_device_id, R.id.status_server, R.id.status_version)) {
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, root.findViewById<View>(hidden).importantForAccessibility)
        }
    }

    @Test fun `the kiosk title reads with its state, and a state change is announced`() {
        val root = s.status(s.kiosk, s.statusState(configured = true)).window.decorView
        val header = root.findViewById<View>(R.id.status_kiosk_header)
        val expected = "${str(R.string.status_label_kiosk)}, ${str(R.string.status_kiosk_off)}"
        assertEquals(expected, header.contentDescription)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, header.accessibilityLiveRegion)
        val returning = root.findViewById<Button>(R.id.status_return)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, returning.accessibilityLiveRegion)
    }

    @Test fun `failure and enrolment-error callouts are announced, read as one`() {
        val failed = s.status(s.kiosk, s.statusState(configured = true, returnFailure = "Could not restore kiosk."))
        val callout = failed.findViewById<View>(R.id.status_return_error)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, callout.accessibilityLiveRegion)
        assertEquals("${str(R.string.kiosk_return_failed_title)}. Could not restore kiosk.", callout.contentDescription)
        s.close()
        val enrolling = s.status(null, s.statusState(deviceId = null, lastError = "Unable to resolve host"))
        val error = enrolling.findViewById<View>(R.id.status_enroll_error)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, error.accessibilityLiveRegion)
        assertTrue(error.contentDescription.startsWith("${str(R.string.status_enrolling_error_title)}. "))
    }

    @Test fun `buttons pad 8dp above and below their label, so they grow with large text`() {
        val a = s.status(s.kiosk, s.statusState(configured = true))
        val b = a.findViewById<Button>(R.id.status_return)
        val px = (BUTTON_PADDING_DP * a.resources.displayMetrics.density).toInt()
        assertEquals(px, b.paddingTop)
        assertEquals(px, b.paddingBottom)
    }

    // ---- kiosk launcher ----

    private fun grid(exit: KioskExit = KioskExit.VISIBLE) =
        KioskScreen.Grid(KioskPalette.DEFAULT, exit, IconSize.MEDIUM, s.apps.map { it.first }, s.kioskApps())

    private fun cells(root: View): ViewGroup = s.find(root, AppGridLayout::class.java)!!

    @Test fun `the launcher's header and hold title are headings, and each screen has a pane title`() {
        val a = s.kioskScreen(grid())
        val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        assertTrue(heading(text(root, str(R.string.kiosk_header))))
        assertEquals(str(R.string.kiosk_header), root.accessibilityPaneTitle)
        s.close()
        val held = s.kioskScreen(KioskScreen.Hold(KioskPalette.DEFAULT, KioskExit.VISIBLE))
        val holdRoot = held.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        assertTrue(heading(text(holdRoot, str(R.string.kiosk_hold_title))))
        assertEquals(str(R.string.kiosk_hold_title), holdRoot.accessibilityPaneTitle)
    }

    @Test fun `the splash announces what opens, its spinner isn't a stop`() {
        val splash = KioskScreen.Splash(KioskPalette.DEFAULT, KioskExit.NONE, "com.example.scanner", "Scanner")
        val a = s.kioskScreen(splash)
        val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        assertEquals(str(R.string.kiosk_opening, "Scanner"), root.accessibilityPaneTitle)
        val spinner = s.find(root, ProgressBar::class.java)!!
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, spinner.importantForAccessibility)
    }

    @Test fun `app cells are buttons`() {
        val cell = cells(s.kioskScreen(grid()).window.decorView).getChildAt(0)
        assertEquals(Button::class.java.name, cell.createAccessibilityNodeInfo().className)
        assertEquals("Inventory", cell.contentDescription)
    }

    @Test fun `app labels take a third line at larger font sizes`() {
        fun maxLines(): Int {
            val cell = cells(s.kioskScreen(grid()).window.decorView).getChildAt(0) as ViewGroup
            return (cell.getChildAt(1) as TextView).maxLines
        }
        assertEquals(2, maxLines())
        s.close()
        RuntimeEnvironment.setFontScale(LARGER_FONT)
        assertEquals(3, maxLines())
    }

    @Test fun `the gesture corner is hidden from accessibility services but still takes taps`() {
        val a = s.kioskScreen(grid(KioskExit.GESTURE))
        val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val corner = (0 until root.childCount).map(root::getChildAt).single { it.javaClass == View::class.java }
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, corner.importantForAccessibility)
        assertTrue(corner.isClickable)
        assertFalse(corner.isFocusable)
    }

    private companion object {
        const val BUTTON_PADDING_DP = 8
        const val LARGER_FONT = 1.15f
    }
}
