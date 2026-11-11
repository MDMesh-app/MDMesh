package com.mdmesh.agent.a11y

import android.app.Dialog
import android.view.View
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult
import com.google.android.apps.common.testing.accessibility.framework.Parameters
import com.google.android.apps.common.testing.accessibility.framework.checks.ImageContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TextSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import com.google.android.apps.common.testing.accessibility.framework.utils.contrast.BitmapImage
import com.mdmesh.agent.ui.IconSize
import com.mdmesh.agent.ui.KioskExit
import com.mdmesh.agent.ui.KioskPalette
import com.mdmesh.agent.ui.KioskScreen
import com.mdmesh.agent.ui.StatusCopy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Google's Accessibility Test Framework (the LATEST preset) on every agent screen and dialog, with a real
 * screenshot so text and icon contrast are measured from pixels: text contrast, touch targets, labels, duplicate
 * clickables, traversal and the rest. Any error or warning fails, except image contrast on decorative images (the
 * kiosk's app icons: other apps' artwork, hidden from accessibility services) and the fixed width of a dialog's window.
 *
 * Run in four configurations (below): light and dark at the default font, the largest font, and the oldest API.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class AtfScreensTest(private val fontScale: Float = 1f) {

    private val s = A11yScreens()

    @Before fun setUp() {
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
        s.seed()
    }

    @After fun tearDown() = s.close()

    private fun check(name: String, root: View) {
        s.idle()
        val hierarchy = AccessibilityHierarchyAndroid.newBuilder(root).build()
        val params = Parameters().apply { putScreenCapture(BitmapImage(s.screenshot(root))) }
        val checks = AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(AccessibilityCheckPreset.LATEST)
        val findings = checks
            .flatMap { it.runCheckOnHierarchy(hierarchy, null, params) }
            .filter { it.type == AccessibilityCheckResultType.ERROR || it.type == AccessibilityCheckResultType.WARNING }
            .filterNot(::decorativeImage)
            .filterNot(::fixedWidthWindow)
            .map { describe(it) }
        assertEquals("$name: ATF findings", emptyList<String>(), findings)
    }

    private fun check(name: String, dialog: Dialog) = check(name, dialog.window!!.decorView)

    /** Only another app's icon in an app cell: its artwork isn't ours to check. Our own icons are checked. */
    private fun decorativeImage(r: AccessibilityHierarchyCheckResult): Boolean =
        r.sourceCheckClass == ImageContrastCheck::class.java &&
            r.element?.resourceName?.endsWith("/kiosk_app_icon") == true

    /**
     * TextSizeCheck's "fixed-width view group with scalable text" on a dialog's window (DecorView): MdmDialog fixes
     * the window's width on purpose (MdmDialog.windowWidth); its height wraps, the text wraps inside it and the body
     * scrolls, so larger text makes the dialog taller, not clipped (checked here at the largest font, too).
     */
    private fun fixedWidthWindow(r: AccessibilityHierarchyCheckResult): Boolean =
        r.sourceCheckClass == TextSizeCheck::class.java &&
            r.resultId == TextSizeCheck.RESULT_ID_FIXED_WIDTH_VIEW_GROUP_WITH_SCALABLE_TEXT &&
            r.element?.className?.endsWith(".DecorView") == true

    private fun describe(r: AccessibilityHierarchyCheckResult): String {
        val e = r.element
        val what = listOfNotNull(e?.className, e?.resourceName, e?.text, e?.contentDescription).joinToString(" ")
        val message = runCatching { r.getMessage(Locale.ENGLISH) }.getOrDefault("result ${r.resultId}")
        return "${r.sourceCheckClass.simpleName} [$what]: $message"
    }

    // ---- status screen ----

    @Test fun statusManaged() = check("status", s.status(null, s.statusState()).window.decorView)

    @Test fun statusNotManaged() =
        check("not managed", s.status(null, s.statusState(deviceOwner = false)).window.decorView)

    @Test fun statusEnrollingWithError() = check(
        "enrolling",
        s.status(null, s.statusState(deviceId = null, lastError = "Unable to resolve host")).window.decorView,
    )

    @Test fun statusKioskReady() =
        check("kiosk ready", s.status(s.kiosk, s.statusState(configured = true)).window.decorView)

    @Test fun statusKioskOn() =
        check("kiosk on", s.status(s.kiosk, s.statusState(configured = true, locked = true)).window.decorView)

    @Test fun statusReturning() =
        check("returning", s.status(s.kiosk, s.statusState(configured = true, returning = true)).window.decorView)

    @Test fun statusReturnFailed() = check(
        "return failed",
        s.status(s.kiosk, s.statusState(configured = true, returnFailure = "Could not restore.")).window.decorView,
    )

    @Test fun statusCopied() = check(
        "copied",
        s.status(s.kiosk, s.statusState(configured = true, copied = StatusCopy.Target.DEVICE_ID)).window.decorView,
    )

    @Test fun returnDialog() = check("return dialog", s.returnDialog(s.kiosk))

    @Test fun returnDialogNoExit() = check("no-exit return dialog", s.returnDialog(s.kioskNoExit))

    // ---- kiosk launcher ----

    private fun grid(palette: KioskPalette, exit: KioskExit = KioskExit.VISIBLE, apps: Boolean = true) =
        KioskScreen.Grid(
            palette, exit, IconSize.MEDIUM, s.apps.map { it.first },
            if (apps) s.kioskApps() else emptyList(),
        )

    private fun kiosk(name: String, screen: KioskScreen) = check(name, s.kioskScreen(screen).window.decorView)

    @Test fun kioskGrid() = kiosk("grid", grid(KioskPalette.DEFAULT))

    @Test fun kioskGridWhite() = kiosk("white grid", grid(KioskPalette.of(WHITE, LIGHT_TEXT)))

    @Test fun kioskGridBrandRed() = kiosk("red grid", grid(KioskPalette.of(BRAND_RED, WHITE)))

    @Test fun kioskGridGreyBlack() = kiosk("grey grid", grid(KioskPalette.of(GREY, BLACK)))

    @Test fun kioskGridGesture() = kiosk("gesture grid", grid(KioskPalette.DEFAULT, KioskExit.GESTURE))

    @Test fun kioskEmpty() = kiosk("empty", grid(KioskPalette.of(BRAND_RED, WHITE), KioskExit.NONE, apps = false))

    /** Over a busy, high-contrast image the adaptive scrim keeps every text and the Exit button readable. */
    @Test fun kioskGridOverBusyImage() =
        check("grid over a busy image", s.kioskScreen(grid(KioskPalette.DEFAULT), s.busyImage()).window.decorView)

    @Test fun kioskGridWhiteOverBusyImage() = check(
        "white grid over a busy image",
        s.kioskScreen(grid(KioskPalette.of(WHITE, LIGHT_TEXT)), s.busyImage()).window.decorView,
    )

    @Test fun kioskHoldOverBusyImage() = check(
        "hold over a busy image",
        s.kioskScreen(KioskScreen.Hold(KioskPalette.of(BRAND_RED, WHITE), KioskExit.VISIBLE), s.busyImage())
            .window.decorView,
    )

    @Test fun kioskIdle() = kiosk("idle", KioskScreen.Idle)

    @Test fun kioskSplash() =
        kiosk("splash", KioskScreen.Splash(KioskPalette.DEFAULT, KioskExit.VISIBLE, "com.example.scanner", "Scanner"))

    @Test fun kioskHold() = kiosk("hold", KioskScreen.Hold(KioskPalette.of(BRAND_RED, WHITE), KioskExit.VISIBLE))

    @Test fun exitPin() = check("exit PIN", s.exitPinDialog())

    @Test fun exitPinWrong() {
        val d = s.exitPinDialog()
        s.wrongPin(d)
        check("wrong PIN", d)
    }

    @Test fun exitLocked() = check("exit locked", s.exitLockedDialog())

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val LIGHT_TEXT = 0xFF1B2230.toInt()
        const val BRAND_RED = 0xFFE53935.toInt() // white on it: 4.2:1
        const val GREY = 0xFF5F6368.toInt() // black on it: 3.5:1
    }
}

@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-420dpi")
class AtfLight35Test : AtfScreensTest()

@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-420dpi")
class AtfDark35Test : AtfScreensTest()

@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-420dpi")
class AtfLargestFont35Test : AtfScreensTest(fontScale = 2f)

@Config(sdk = [26], qualifiers = "w360dp-h640dp-port-notnight-xhdpi")
class AtfLight26Test : AtfScreensTest()

