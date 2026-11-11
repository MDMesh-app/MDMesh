package com.mdmesh.agent.stress

import android.app.ActivityManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewGroup
import com.mdmesh.agent.ui.BackdropDrawable
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

    @Test fun textOverTheImageSitsOnAPlate() {
        cache()
        launch(withImage(grid))
        val texts = texts(activity.findViewById(android.R.id.content))
        assertTrue("some text shown", texts.isNotEmpty())
        assertTrue("every text has a plate", texts.all { it.background != null })
    }

    private fun texts(v: android.view.View): List<android.widget.TextView> = when (v) {
        is android.widget.Button -> emptyList() // buttons carry their own fill
        is android.widget.TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun noImageMeansThePlainColour() {
        launch(grid)
        assertFalse(backdrop() != null)
    }
}
