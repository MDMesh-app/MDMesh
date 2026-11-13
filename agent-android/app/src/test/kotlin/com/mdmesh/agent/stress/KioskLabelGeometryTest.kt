package com.mdmesh.agent.stress

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.mdmesh.agent.ui.LabelRows
import com.mdmesh.agent.ui.LabelView
import com.mdmesh.agent.ui.TextPlate
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskBackgroundImageDto
import com.mdmesh.proto.KioskThemeDto
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The label geometry the spec pins, on the real laid-out launcher, at several densities and shapes, with and
 * without an image: every label's text box is its cell less 24dp (within a pixel, never wider), row plates keep 8dp
 * around their text, and cells keep their 8dp/12dp padding.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class KioskLabelGeometryTest(private val withImage: Boolean) : KioskHarness() {

    private fun cachedWhite(): String {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val h = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        File(app.filesDir, "kiosk-background").also { it.mkdirs() }.let { File(it, "$h.image").writeBytes(bytes) }
        return h
    }

    private val names = listOf(
        "Inventory", "Maintenance", "Maintenance Requests", "Supercalifragilistic", "Time Clock", "Point-of-Sale",
        "Warehouse Order Picking", "Shift Swap",
    )

    private fun kiosk(): KioskApplyPayload {
        val pkgs = names.map { "com.geo.$it" }.onEach { install(it) }
        val image = KioskBackgroundImageDto("https://127.0.0.1:1/g.png", cachedWhite())
        val theme = if (withImage) KioskThemeDto(backgroundImage = image) else KioskThemeDto()
        return grid.copy(allowedPackages = pkgs, theme = theme)
    }

    private fun labels(v: View): List<LabelView> = when (v) {
        is LabelView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { labels(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun textBoxCellPaddingAndPlatePadding() {
        launch(kiosk())
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(17))
            decor.viewTreeObserver.dispatchOnPreDraw()
        }
        val d = decor.resources.displayMetrics.density
        val all = labels(decor)
        check(all.size == names.size)
        for (l in all) {
            val cell = l.parent as View
            val box = l.width - l.totalPaddingLeft - l.totalPaddingRight
            val spec = LabelRows.textBox(cell.width / d.toDouble()) * d
            assertTrue("'${l.label}': box $box px, spec $spec px", box <= spec + 0.01 && box >= spec - 1.0)
            val padded = cell.paddingLeft == (8 * d).roundToInt() && cell.paddingTop == (12 * d).roundToInt()
            assertTrue("cell padding ${cell.paddingLeft}/${cell.paddingTop} px", padded)
            val lay = l.layout
            val ink = (0 until lay.lineCount).maxOf { lay.getLineRight(it) - lay.getLineLeft(it) }
            assertTrue("'${l.label}': a line ($ink px) fits the box", ink <= spec + 0.5)
            val plate = l.background as? TextPlate ?: continue
            val r = plate.plateRect(RectF())
            val left = l.totalPaddingLeft + (0 until lay.lineCount).minOf { lay.getLineLeft(it) }
            val right = l.totalPaddingLeft + (0 until lay.lineCount).maxOf { lay.getLineRight(it) }
            assertTrue("'${l.label}': plate padding ${(left - r.left) / d}dp / ${(r.right - right) / d}dp",
                left - r.left >= 8 * d - 1 && r.right - right >= 8 * d - 1)
        }
    }
}

@Config(qualifiers = "w411dp-h891dp-port-notnight-420dpi")
class KioskLabelGeometry411Image : KioskLabelGeometryTest(true)

@Config(qualifiers = "w411dp-h891dp-port-notnight-420dpi")
class KioskLabelGeometry411NoImage : KioskLabelGeometryTest(false)

@Config(qualifiers = "w360dp-h640dp-port-notnight-xhdpi")
class KioskLabelGeometry360Image : KioskLabelGeometryTest(true)

@Config(qualifiers = "w360dp-h640dp-port-notnight-xhdpi")
class KioskLabelGeometry360NoImage : KioskLabelGeometryTest(false)

@Config(qualifiers = "w800dp-h1280dp-port-notnight-mdpi")
class KioskLabelGeometry800Image : KioskLabelGeometryTest(true)

@Config(qualifiers = "w800dp-h1280dp-port-notnight-mdpi")
class KioskLabelGeometry800NoImage : KioskLabelGeometryTest(false)

@Config(qualifiers = "w891dp-h411dp-land-notnight-420dpi")
class KioskLabelGeometry891LandImage : KioskLabelGeometryTest(true)

@Config(qualifiers = "w891dp-h411dp-land-notnight-420dpi")
class KioskLabelGeometry891LandNoImage : KioskLabelGeometryTest(false)
