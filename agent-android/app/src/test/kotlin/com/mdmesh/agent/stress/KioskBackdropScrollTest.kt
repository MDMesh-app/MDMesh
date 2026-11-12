package com.mdmesh.agent.stress

import android.graphics.Bitmap
import android.os.Looper
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.mdmesh.agent.ui.Contrast
import com.mdmesh.agent.ui.KioskPalette
import com.mdmesh.agent.ui.KioskScrim
import com.mdmesh.agent.ui.TextPlate
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskBackgroundImageDto
import com.mdmesh.proto.KioskThemeDto
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The image stays put while the grid scrolls over it: every label's plate follows what is under it now, on any
 * scroll position (programmatic, fling, restored), not where the label was at the last layout.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-port-notnight-mdpi")
class KioskBackdropScrollTest : KioskHarness() {

    /** White top half, black bottom half: light labels need plates over the top, none over the bottom. */
    private fun cachedHalfWhite(): String {
        val bmp = Bitmap.createBitmap(36, 64, Bitmap.Config.ARGB_8888)
        for (x in 0 until 36) for (y in 0 until 64) bmp.setPixel(x, y, if (y < 32) Color.WHITE else Color.BLACK)
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val h = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        File(app.filesDir, "kiosk-background").also { it.mkdirs() }.let { File(it, "$h.image").writeBytes(bytes) }
        return h
    }

    private fun manyApps(): KioskApplyPayload {
        val pkgs = (0 until 60).map { "com.example.app$it" }.onEach { install(it) }
        val image = KioskBackgroundImageDto("https://127.0.0.1:1/x.png", cachedHalfWhite())
        return grid.copy(allowedPackages = pkgs, theme = KioskThemeDto(backgroundImage = image))
    }

    private fun labels(v: View): List<TextView> = when (v) {
        is TextView -> if ((v.background as? TextPlate)?.inRow == true && v.isShown) listOf(v) else emptyList()
        is ViewGroup -> (0 until v.childCount).flatMap { labels(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun scrollView(v: View): ScrollView? = when (v) {
        is ScrollView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { scrollView(v.getChildAt(it)) }
        else -> null
    }

    /**
     * Lets [n] display frames pass. Robolectric runs no draw traversal for a bare invalidation, so each frame's
     * pre-draw (where the launcher works its plates out) is dispatched here, as the device's frame would.
     */
    private fun frames(n: Int) = repeat(n) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
        decor.viewTreeObserver.dispatchOnPreDraw()
    }

    private fun steps() = labels(decor).map { (it.background as TextPlate).step }

    private fun overWhite() = labels(decor).filter { l ->
        val y = IntArray(2).also(l::getLocationInWindow)[1]
        y > 0 && y + l.height < 300
    }

    @Test fun platesFollowAScrolledGrid() {
        launch(manyApps())
        val sv = scrollView(decor)!!
        assertTrue("the grid scrolls", sv.getChildAt(0).height > sv.height)
        // No stretch or glow: text is drawn where it scrolled to.
        assertEquals(View.OVER_SCROLL_NEVER, sv.overScrollMode)
        sv.scrollTo(0, 400)
        frames(1)
        val now = overWhite()
        assertTrue("labels now over the white half", now.isNotEmpty())
        assertTrue("each has a plate", now.all { (it.background as TextPlate).step > 0 })
        // White is the brightest pixel there is, and the dim only darkens it: each label reads at 4.5:1 or more.
        val bg = KioskPalette.DEFAULT.background
        for (l in now) {
            val seen = KioskScrim.blend(bg, Color.WHITE, (l.background as TextPlate).step)
            val ratio = Contrast.ratio(l.currentTextColor, seen)
            assertTrue("${l.text}: $ratio", ratio >= Contrast.TEXT_MIN)
        }
        val afterScroll = steps()
        decor.requestLayout()
        settle()
        frames(1)
        assertEquals("the same as a fresh layout", steps(), afterScroll)
    }

    /**
     * A real fling, frame by frame: its scroller moves in `computeScroll`, inside the draw pass after the pre-draw. At
     * each drawn frame (no pre-draw in between) every label's plate already matches where the label is drawn.
     */
    @Test fun platesMatchEveryDrawnFrameOfAFling() {
        launch(manyApps())
        val sv = scrollView(decor)!!
        sv.fling(6000)
        var moved = 0
        repeat(60) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
            val before = sv.scrollY
            sv.computeScroll() // what this frame's draw does
            if (sv.scrollY != before) moved++
            for (l in labels(decor)) {
                val y = IntArray(2).also(l::getLocationInWindow)[1]
                val step = (l.background as TextPlate).step
                if (y >= 0 && y + l.height < WHITE_END) assertTrue("frame $it: '${l.text}' over white at $y", step > 0)
                if (y > BLACK_START && y + l.height < decor.height) {
                    assertEquals("frame $it: '${l.text}' over black", 0, step)
                }
            }
        }
        assertTrue("the fling ran ($moved frames moved)", moved > 5)
    }

    @Test fun platesFollowAFlingAndScrollingBack() {
        launch(manyApps())
        val sv = scrollView(decor)!!
        // A fling, frame by frame (Robolectric draws no frames, so its scroller doesn't run: the positions are
        // stepped through as a fling would, several scrolls per frame at first).
        var y = 0
        for (step in listOf(120, 120, 90, 90, 60, 40, 25, 10, 5)) {
            sv.scrollTo(0, y + step / 2)
            y += step
            sv.scrollTo(0, y)
            frames(1)
        }
        assertTrue("the grid moved", sv.scrollY > 0)
        val flung = steps()
        decor.requestLayout()
        settle()
        frames(1)
        assertEquals("after a fling: as a fresh layout", steps(), flung)
        sv.scrollTo(0, 0)
        frames(2)
        val back = steps()
        decor.requestLayout()
        settle()
        frames(1)
        assertEquals("scrolled back: as a fresh layout", steps(), back)
    }

    private companion object {
        const val FRAME_MS = 17L
        /** The image's white half ends at 320 px in this 640 px window; keep clear of the edge cells. */
        const val WHITE_END = 300
        const val BLACK_START = 340
    }
}
