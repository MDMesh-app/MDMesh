package com.mdmesh.agent.stress

import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskBackgroundImageDto
import com.mdmesh.proto.KioskThemeDto
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PR #192 S1: on Android 15 the bars are transparent over the kiosk's image, and each bar's icons are dark or light
 * from the pixels under that bar: the status bar's from the top strip, the navigation bar's from its own strip (the
 * bottom with 3-button or gesture navigation in portrait, a side with 3-button navigation in landscape). An image
 * bright on top and dark below must not give the Back button black icons on near-black.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class KioskBarIconsTest : KioskHarness() {

    /** A 64 x 64 PNG, [first] in the top (or left, [across]) half and [second] in the other. */
    private fun halves(first: Int, second: Int, across: Boolean): KioskBackgroundImageDto {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (x in 0 until 64) for (y in 0 until 64) {
            bmp.setPixel(x, y, if ((if (across) x else y) < 32) first else second)
        }
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
        File(app.filesDir, "kiosk-background").also { it.mkdirs() }.resolve("$hash.image").writeBytes(png)
        return KioskBackgroundImageDto("https://127.0.0.1:1/kiosk.png", hash)
    }

    private fun withImage(k: KioskApplyPayload, image: KioskBackgroundImageDto) =
        k.copy(theme = KioskThemeDto(backgroundImage = image))

    private fun root() = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    /**
     * The window's bars as the system reports them: the status bar on top, the navigation bar at [nav]. Robolectric
     * reports no bars, so every insets dispatch from the decor view down carries these instead (a re-dispatch after
     * a layout included), as on a phone.
     */
    private fun bars(nav: Insets) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_PX, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), nav)
            .build()
        val decor = activity.window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(decor) { v, _ -> ViewCompat.onApplyWindowInsets(v, insets) }
        ViewCompat.requestApplyInsets(decor)
        settle()
        assertEquals("the root is padded clear of the bars", nav.left, root().paddingLeft)
        assertEquals(nav.bottom, root().paddingBottom)
    }

    private val controller get() = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

    private fun check(nav: Insets, statusDarkIcons: Boolean, navDarkIcons: Boolean) {
        bars(nav)
        assertEquals("status bar icons ($nav)", statusDarkIcons, controller.isAppearanceLightStatusBars)
        assertEquals("navigation bar icons ($nav)", navDarkIcons, controller.isAppearanceLightNavigationBars)
    }

    /** Portrait, 3-button navigation (a 48 dp bar) and gesture navigation (a thin handle strip). */
    @Test fun portraitNavigationIconsFollowTheBottomOfTheImage() {
        launch(withImage(grid, halves(Color.WHITE, Color.BLACK, across = false)))
        check(Insets.of(0, 0, 0, THREE_BUTTON_PX), statusDarkIcons = true, navDarkIcons = false)
        check(Insets.of(0, 0, 0, GESTURE_PX), statusDarkIcons = true, navDarkIcons = false)
    }

    @Test fun portraitDarkTopLightBottomIsTheOtherWayRound() {
        launch(withImage(grid, halves(Color.BLACK, Color.WHITE, across = false)))
        check(Insets.of(0, 0, 0, THREE_BUTTON_PX), statusDarkIcons = false, navDarkIcons = true)
        check(Insets.of(0, 0, 0, GESTURE_PX), statusDarkIcons = false, navDarkIcons = true)
    }

    /** Landscape phone, 3-button navigation: the bar is down one side; its icons follow that side. */
    @Test @Config(qualifiers = "w1280dp-h800dp-land-notnight-mdpi")
    fun landscapeSideNavigationIconsFollowThatSide() {
        launch(withImage(grid, halves(Color.WHITE, Color.BLACK, across = true)))
        bars(Insets.of(0, 0, THREE_BUTTON_PX, 0))
        assertEquals("bar on the dark right", false, controller.isAppearanceLightNavigationBars)
        bars(Insets.of(THREE_BUTTON_PX, 0, 0, 0))
        assertEquals("bar on the light left", true, controller.isAppearanceLightNavigationBars)
    }

    private companion object {
        const val STATUS_PX = 24
        const val THREE_BUTTON_PX = 48
        const val GESTURE_PX = 16
    }
}
