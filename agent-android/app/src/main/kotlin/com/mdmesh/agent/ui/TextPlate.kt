package com.mdmesh.agent.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.Insets
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A text plate: a rounded rectangle of the kiosk background colour behind one text over the background image.
 * It hugs the text ([padH] either side, the view's full height) and is centred on it; a grid label's plate is
 * placed by its row instead ([rowPlate], [LabelRows]). Its opacity ([step] of [KioskScrim.STEPS], 0: not
 * drawn) is the least at which the text reaches 4.5:1 over every pixel under it ([TextPlates.update]).
 */
class TextPlate(
    private val view: TextView,
    private val colour: Int,
    private val radius: Float,
    private val padH: Int,
    /** A grid label: shares its row's width. */
    val inRow: Boolean,
) : Drawable() {

    var step: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    /** A grid label's plate, from its row: left and width in the view's own coordinates. */
    var rowPlate: Pair<Float, Float>? = null
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** The widest laid-out line; 0 before layout or without text. */
    fun textWidth(): Float {
        val layout = view.layout?.takeIf { it.lineCount > 0 && !view.text.isNullOrEmpty() } ?: return 0f
        var w = 0f
        for (i in 0 until layout.lineCount) w = maxOf(w, layout.getLineRight(i) - layout.getLineLeft(i))
        return w
    }

    /** Where the plate is, in the view's own coordinates; empty when there is nothing to back. */
    fun plateRect(out: RectF): RectF {
        val layout = view.layout
        val natural = if (textWidth() > 0f) ceil(textWidth()).toInt() + 2 * padH else 0
        val row = rowPlate
        when {
            layout == null || natural == 0 -> out.setEmpty()
            row != null -> out.set(row.first, 0f, row.first + row.second, view.height.toFloat())
            else -> {
                var left = Float.MAX_VALUE
                var right = -Float.MAX_VALUE
                for (i in 0 until layout.lineCount) {
                    left = minOf(left, layout.getLineLeft(i))
                    right = maxOf(right, layout.getLineRight(i))
                }
                val centre = view.compoundPaddingLeft + (left + right) / 2f
                val width = minOf(view.width, natural).toFloat()
                val l = (centre - width / 2f).coerceIn(0f, maxOf(0f, view.width - width))
                out.set(l, 0f, l + width, view.height.toFloat())
            }
        }
        return out
    }

    override fun draw(canvas: Canvas) {
        if (step <= 0) return
        plateRect(rect)
        if (rect.isEmpty) return
        paint.color = ((step * BYTE + KioskScrim.STEPS / 2) / KioskScrim.STEPS shl ALPHA) or (colour and RGB)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val BYTE = 255
        const val ALPHA = 24
        const val RGB = 0x00FFFFFF
    }
}

/**
 * Works a launcher root's plates out again whenever what is under them may have moved:
 * - after any layout (rotation, new apps, a restored scroll position) or when told ([changed]: a new image or look),
 *   in the next frame's pre-draw, at most once a frame;
 * - at once on every scroll it [follow]s. A fling moves in the scroller's `computeScroll`, inside the draw pass and
 *   after the pre-draw, so a deferred update would draw each frame with the last frame's plates; updated in the scroll
 *   callback, they are invalidated before the scroll view records its children and drawn in the same frame.
 * The views are scanned once per layout; a scroll only moves positions (no tree walk, few allocations).
 */
class PlateFrames(private val root: ViewGroup, private val update: (TextPlates.Scan) -> Unit) {
    private var scan: TextPlates.Scan? = null
    private var dirty = false

    init {
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> viewsChanged() }
        root.viewTreeObserver.addOnPreDrawListener {
            if (dirty) {
                dirty = false
                update(scan ?: TextPlates.scan(root).also { scan = it })
            }
            true
        }
    }

    /** Before the next frame; several calls in one frame cost one update. */
    fun changed() {
        if (!dirty) {
            dirty = true
            root.postInvalidateOnAnimation()
        }
    }

    /** The views under the root were replaced or laid out again: scan them again too. */
    fun viewsChanged() {
        scan = null
        changed()
    }

    /** [scroller] moves text over the (fixed) image: its plates follow in the same frame. */
    fun follow(scroller: View) {
        scroller.setOnScrollChangeListener { _, _, _, _, _ ->
            val now = scan
            if (now != null && !dirty) update(now) else changed()
        }
    }
}

/** Works out every [TextPlate] under a launcher root: row widths first, then each plate's opacity. */
object TextPlates {

    /** The tag of a control the dim serves (the Exit button): it has its own fill and gets no plate. */
    val CONTROL = Any()

    /** What [update] worked out: the dim, and the colours under the status and navigation bars (for their icons). */
    data class Painted(val dim: Int, val statusBar: Int, val navigationBar: Int)

    /**
     * What a layout pass found under a launcher root: the plated texts, the controls, and the grid rows (by layout
     * position, which scrolling doesn't change). Made once per layout ([scan]); every scroll frame reuses it.
     */
    class Scan internal constructor(
        val plates: List<Pair<TextView, TextPlate>>,
        val controls: List<View>,
        val rows: List<List<Pair<TextView, TextPlate>>>,
    ) {
        internal val origin = IntArray(2)
        internal val at = IntArray(2)
        internal val rect = RectF()
        internal val visible = android.graphics.Rect()
    }

    /** After a layout: finds the plated texts and controls under [root] and places the row plates ([LabelRows]). */
    fun scan(root: ViewGroup): Scan {
        val plates = mutableListOf<Pair<TextView, TextPlate>>()
        val controls = mutableListOf<View>()
        collect(root, plates, controls)
        // A row: the labels of cells with the same top in the same grid (layout coordinates, not the window's).
        val rows = plates.filter { it.second.inRow && it.first.isShown }
            .groupBy { (view, _) -> (view.parent as View).let { it.parent to it.top } }
            .values.toList()
        val scan = Scan(plates, controls, rows)
        layOutRows(root, scan)
        return scan
    }

    /** What [update] draws with: the kiosk's colours, the image's placement, and where the navigation bar is. */
    class Look(
        val palette: KioskPalette,
        val placement: Placement,
        /** The window's navigation-bar insets (none before Android 15). */
        val navBar: Insets = Insets.NONE,
    )

    /**
     * [root] is drawn over the image described by [info] (null: no image, no plates) by the root's
     * [BackdropDrawable] in [look]'s palette and placement. Works out the dim from the controls, then every plate's
     * opacity, from where each one is now (scrolled or not); `docs/kiosk-background-scrim.md`. Null without an
     * image. An image decoded over another background colour (a recolour; a new decode is on its way) is only
     * trusted where it has no transparency; otherwise every plate is solid and the dim at its cap until then.
     */
    fun update(root: ViewGroup, scan: Scan, info: BackdropInfo?, look: Look): Painted? =
        when {
            info == null || root.width == 0 || root.height == 0 -> {
                scan.plates.forEach { it.second.step = 0 }
                null
            }
            !info.validFor(look.palette.background) -> provisional(root, scan, look.palette)
            else -> measured(root, scan, info, look)
        }

    /** Until an image with transparency is decoded over the new colour: every plate solid, the dim at its cap. */
    private fun provisional(root: ViewGroup, scan: Scan, palette: KioskPalette): Painted {
        scan.plates.forEach { it.second.step = KioskScrim.STEPS }
        (root.background as? BackdropDrawable)?.dim = LegibilityV3.DIM_MAX_STEP
        return Painted(LegibilityV3.DIM_MAX_STEP, palette.background, palette.background)
    }

    private fun measured(root: ViewGroup, scan: Scan, info: BackdropInfo, look: Look): Painted {
        val palette = look.palette
        root.getLocationInWindow(scan.origin)
        val w = root.width.toDouble()
        val h = root.height.toDouble()
        val image = BackdropGeometry.rect(info.cells.width, info.cells.height, w, h, look.placement)
        /** The bounds under [r], given in [v]'s coordinates. */
        fun bounds(v: View, r: RectF): ColourBox {
            v.getLocationInWindow(scan.at)
            val x = (scan.at[0] - scan.origin[0] + r.left).toDouble()
            val y = (scan.at[1] - scan.origin[1] + r.top).toDouble()
            val region = BackdropCells.Region(x, y, r.width().toDouble(), r.height().toDouble())
            return info.cells.bounds(image, w, h, region, palette.background)
        }
        val rule = BackdropRules.current
        val dim = dim(rule, palette, scan.controls.filter { it.isShown }.map { bounds(it, scan.rect.whole(it)) })
        (root.background as? BackdropDrawable)?.dim = dim
        for ((view, plate) in scan.plates) {
            plate.plateRect(scan.rect)
            when {
                scan.rect.isEmpty || !view.isShown -> plate.step = 0
                // Off screen (scrolled out of the grid's viewport): nothing of it is drawn, so it isn't worked out on
                // this frame; the frame that scrolls it into view does (the scroll callback updates at once).
                !view.getGlobalVisibleRect(scan.visible) -> Unit
                else -> plate.step =
                    rule.plateStep(view.currentTextColor, palette.background, bounds(view, scan.rect), dim)
            }
        }
        // The labels of a grid row are plated alike: the row's step for each of them.
        for (row in scan.rows) {
            val shared = rule.rowStep(row.map { it.second.step })
            row.forEach { it.second.step = shared }
        }
        // Each bar's icons from the pixels under that bar, dimmed as drawn: the status bar's strip is as tall as the
        // top inset, the navigation bar's lies along its own edge (the bottom, or a side in landscape).
        fun under(strip: RectF): Int {
            val bar = bounds(root, strip)
            return KioskScrim.blend(palette.background, KioskScrim.blend(bar.min, bar.max, KioskScrim.STEPS / 2), dim)
        }
        scan.rect.set(0f, 0f, root.width.toFloat(), maxOf(1, root.paddingTop).toFloat())
        val statusBar = under(scan.rect)
        val navigationBar = under(navigationStrip(root.width, root.height, look.navBar, scan.rect))
        return Painted(dim, statusBar, navigationBar)
    }

    private fun dim(rule: BackdropRule, palette: KioskPalette, controls: List<ColourBox>): Int {
        val dim = rule.dimStep(palette, controls)
        if (rule is LegibilityV3 && rule.controlsShort(palette, controls, dim)) {
            Log.w(TAG, "kiosk background: the Exit button stays under 3:1 even at the 30% dim")
        }
        return dim
    }

    private fun RectF.whole(v: View): RectF = apply { set(0f, 0f, v.width.toFloat(), v.height.toFloat()) }

    /** [v]'s left edge in [root]'s coordinates. */
    private fun leftIn(root: View, v: View): Float {
        val origin = IntArray(2).also(root::getLocationInWindow)
        val at = IntArray(2).also(v::getLocationInWindow)
        return (at[0] - origin[0]).toFloat()
    }

    /**
     * Each grid row's plates by [LabelRows] (in dp, as the spec): one width, centred under the icons (the cells'
     * centres), kept inside the grid's content edges (its first cell's start, its last cell's end). Horizontal only,
     * so scrolling doesn't move them within their views.
     */
    private fun layOutRows(root: View, scan: Scan) {
        val density = root.resources.displayMetrics.density.toDouble()
        for (row in scan.rows) {
            val cells = row.map { it.first.parent as View }
            val grid = cells.first().parent as? ViewGroup ?: continue
            val all = (0 until grid.childCount).map(grid::getChildAt).filter { it.visibility == View.VISIBLE }
            val gridX = leftIn(root, grid)
            val left = (gridX + all.minOf { it.left }) / density
            val right = (gridX + all.maxOf { it.right }) / density
            val centres = cells.map { (leftIn(root, it) + it.width / 2f) / density }
            // The text's width rounded up to whole pixels, as a single plate's.
            val widths = row.map { ceil(it.second.textWidth()) / density }
            val plates = LabelRows.plates(centres, widths, cells.first().width / density, left, right)
            row.forEachIndexed { i, (view, plate) ->
                val x = (plates[i].x * density - leftIn(root, view)).toFloat()
                plate.rowPlate = x to (plates[i].w * density).toFloat()
            }
        }
    }

    private fun collect(v: View, plates: MutableList<Pair<TextView, TextPlate>>, controls: MutableList<View>) {
        if (v.tag === CONTROL) controls += v
        if (v is TextView) (v.background as? TextPlate)?.let { plates += v to it }
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), plates, controls)
    }

    private const val TAG = "KioskBackdrop"

    /** The plate's corner radius for [density]: the console's small radius (`--radius-sm`, 10px). */
    fun radius(density: Float): Float = (RADIUS_DP * density).roundToInt().toFloat()

    const val RADIUS_DP = 10
}

/**
 * The strip under the navigation bar in a [width] x [height] root drawn under the bars: along the edge of the
 * largest [nav] inset (bottom with gesture or 3-button navigation, left or right with a 3-button bar on a
 * landscape phone). No navigation bar reported: the bottom pixel row, where a gesture handle would be.
 */
internal fun navigationStrip(width: Int, height: Int, nav: Insets, into: RectF): RectF {
    val w = width.toFloat()
    val h = height.toFloat()
    val side = maxOf(nav.left, nav.right)
    return when {
        nav.bottom >= side && nav.bottom > 0 -> into.apply { set(0f, h - nav.bottom, w, h) }
        nav.right >= nav.left && nav.right > 0 -> into.apply { set(w - nav.right, 0f, w, h) }
        nav.left > 0 -> into.apply { set(0f, 0f, nav.left.toFloat(), h) }
        else -> into.apply { set(0f, h - 1f, w, h) }
    }
}
