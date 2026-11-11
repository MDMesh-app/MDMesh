package com.mdmesh.agent.stress

import android.app.ActivityManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewGroup
import com.mdmesh.agent.R
import com.mdmesh.agent.ui.BackdropDrawable
import com.mdmesh.agent.ui.KioskScrim
import com.mdmesh.agent.ui.TextPlate
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskBackgroundImageDto
import com.mdmesh.proto.KioskThemeDto
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

/**
 * The kiosk background image on the real launcher (it replaces #170's source-shape tests): painted as the root's
 * background, kept through in-place rebuilds, retried on every return to the front, and never a reason to reopen
 * the pinned app or to lift a crash-loop hold.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KioskBackdropStressTest : KioskHarness() {

    /** A real PNG; its hash names the cache file the store would have written after a verified download. */
    private val png: ByteArray by lazy {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (x in 0 until 64) for (y in 0 until 64) {
            bmp.setPixel(x, y, if ((x / 8 + y / 8) % 2 == 0) Color.BLACK else Color.WHITE)
        }
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
    private val hash by lazy {
        MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
    }
    /** A URL that fails at once (nothing listens), so only the cache can supply the image. */
    private val image by lazy { KioskBackgroundImageDto("https://127.0.0.1:1/kiosk.png", hash) }

    private fun cache() {
        val dir = File(app.filesDir, "kiosk-background").also { it.mkdirs() }
        File(dir, "$hash.image").writeBytes(png)
    }

    private fun withImage(k: KioskApplyPayload) = k.copy(theme = KioskThemeDto(backgroundImage = image))

    private fun backdrop() = (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)).background
        as? BackdropDrawable

    @Test fun imageIsTheRootBackgroundAndSurvivesARebuild() {
        cache()
        launch(withImage(grid))
        assertTrue("image painted", backdrop() != null)
        val rotated = Configuration(activity.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        ctl!!.configurationChange(rotated)
        settle()
        assertTrue("still painted after an in-place rebuild", backdrop() != null)
    }

    @Test fun anArrivingImageNeverReopensThePinnedApp() {
        cache()
        launch(withImage(single))
        assertEquals("opened once, not again when its backdrop arrived", 1, pinnedStarts())
    }

    @Test fun anImageDoesNotLiftACrashLoopHold() {
        cache()
        launch(withImage(single))
        bounce(4)
        assertTrue(holdShowing())
        drainStarts()
        coverAndReturn()
        assertTrue("still holding", holdShowing())
        assertEquals(0, pinnedStarts())
    }

    @Test fun aMissingImageIsRetriedWhenHomeComesBack() {
        launch(withImage(grid))
        assertFalse("not there yet: colour", backdrop() != null)
        cache()
        coverAndReturn()
        assertTrue("found on the next return to the front", backdrop() != null)
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
    }

    @Test fun textOverTheImageHasHuggingPlatesSharedPerRow() {
        cache()
        launch(withImage(grid))
        val plated = texts(activity.findViewById(android.R.id.content))
        assertTrue("some text shown", plated.isNotEmpty())
        assertTrue("every text off a fill of its own has a plate", plated.all { it.background is TextPlate })
        // A black and white checkerboard: plates are solid where the scrim alone leaves a text under 4.5:1, none where
        // it doesn't.
        assertTrue(plated.all { (it.background as TextPlate).step.let { s -> s == 0 || s == KioskScrim.STEPS } })
        val header = plated.first { it.text == s(R.string.kiosk_header) }
        val rect = (header.background as TextPlate).plateRect(android.graphics.RectF())
        assertTrue("the header plate hugs its text", rect.width() < header.width)
        val labels = plated.filter { (it.background as TextPlate).inRow }
        labels.groupBy { IntArray(2).also(it::getLocationInWindow)[1] }.values.forEach { row ->
            val widths = row.map { (it.background as TextPlate).plateRect(android.graphics.RectF()).width() }.toSet()
            assertEquals("one width per row", 1, widths.size)
        }
    }

    @Test fun aNewUrlForTheSameImageChangesNothing() {
        cache()
        launch(withImage(single))
        val before = backdrop()!!.bitmap
        drainStarts()
        val moved = withImage(single).let { k ->
            k.copy(theme = k.theme.copy(backgroundImage = image.copy(url = "https://elsewhere.example/kiosk.png")))
        }
        saveKiosk(moved)
        settle()
        assertEquals("no re-pin, no relaunch", 0, pinnedStarts())
        assertTrue("the same bitmap, no flash of the colour", backdrop()!!.bitmap === before)
    }

    /** TextViews not inside a filled container or a button (those carry their own fill). */
    private fun texts(v: android.view.View): List<android.widget.TextView> = when (v) {
        is android.widget.Button -> emptyList()
        is android.widget.TextView -> listOf(v)
        is ViewGroup -> if (v.background is android.graphics.drawable.GradientDrawable) {
            emptyList()
        } else {
            (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        }
        else -> emptyList()
    }

    @Test fun noImageMeansThePlainColour() {
        launch(grid)
        assertFalse(backdrop() != null)
    }
}
