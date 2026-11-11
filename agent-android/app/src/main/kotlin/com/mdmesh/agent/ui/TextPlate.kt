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

/** Works out every [TextPlate] under a launcher root: row widths first, then each plate's opacity. */
object TextPlates {

    /** The tag of a control the dim serves (the Exit button): it has its own fill and gets no plate. */
    val CONTROL = Any()

    /** What [update] worked out: the dim, and the colour under the status bar (for its icons). */
    data class Painted(val dim: Int, val statusBar: Int)

    /**
     * [root] is laid out over the image described by [info] (null: no image, no plates), drawn by the root's
     * [BackdropDrawable] in [palette] and [placement]. Places the row plates, works out the dim from the controls,
     * then every plate's opacity (`docs/kiosk-background-scrim.md`). Null without an image.
     */
    fun update(root: ViewGroup, info: BackdropInfo?, palette: KioskPalette, placement: Placement): Painted? {
        val plates = mutableListOf<Pair<TextView, TextPlate>>()
        val controls = mutableListOf<View>()
        collect(root, plates, controls)
        layOutRows(root, rows(plates))
        if (info == null || root.width == 0 || root.height == 0) {
            plates.forEach { it.second.step = 0 }
            return null
        }
        val w = root.width.toDouble()
        val h = root.height.toDouble()
        val image = BackdropGeometry.rect(info.cells.width, info.cells.height, w, h, placement)
        fun bounds(r: RectF): ColourBox {
            val region = BackdropCells.Region(
                r.left.toDouble(), r.top.toDouble(), r.width().toDouble(), r.height().toDouble(),
            )
            return info.cells.bounds(image, w, h, region, palette.background)
        }
        val rule = BackdropRules.current
        val controlBounds = controls.filter { it.isShown }
            .map { bounds(inRoot(root, it, RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()))) }
        val dim = rule.dimStep(palette, controlBounds)
        if (rule is LegibilityV3 && rule.controlsShort(palette, controlBounds, dim)) {
            Log.w(TAG, "kiosk background: the Exit button stays under 3:1 even at the 30% dim")
        }
        (root.background as? BackdropDrawable)?.dim = dim
        val rect = RectF()
        for ((view, plate) in plates) {
            plate.plateRect(rect)
            plate.step = if (rect.isEmpty || !view.isShown) {
                0
            } else {
                rule.plateStep(view.currentTextColor, palette.background, bounds(inRoot(root, view, rect)), dim)
            }
        }
        // The labels of a grid row are plated alike: the row's step for each of them.
        rows(plates).forEach { row ->
            val shared = rule.rowStep(row.map { it.second.step })
            row.forEach { it.second.step = shared }
        }
        val bar = bounds(RectF(0f, 0f, root.width.toFloat(), maxOf(1, root.paddingTop).toFloat()))
        val barMiddle = KioskScrim.blend(bar.min, bar.max, KioskScrim.STEPS / 2)
        val barColour = KioskScrim.blend(palette.background, barMiddle, dim)
        return Painted(dim, barColour)
    }

    /** [r] (in [v]'s coordinates) moved into [root]'s. */
    private fun inRoot(root: View, v: View, r: RectF): RectF {
        val origin = IntArray(2).also(root::getLocationInWindow)
        val at = IntArray(2).also(v::getLocationInWindow)
        r.offset((at[0] - origin[0]).toFloat(), (at[1] - origin[1]).toFloat())
        return r
    }

    /** The shown grid labels, by row (labels of a row share their top). */
    private fun rows(plates: List<Pair<TextView, TextPlate>>): Collection<List<Pair<TextView, TextPlate>>> {
        val at = IntArray(2)
        return plates.filter { it.second.inRow && it.first.isShown }
            .groupBy { at.also(it.first::getLocationInWindow)[1] }
            .values
    }

    /**
     * Each grid row's plates by [LabelRows] (in dp, as the spec): one width, centred under the icons (the cells'
     * centres), kept inside the grid's content edges (its first cell's start, its last cell's end).
     */
    private fun layOutRows(root: View, rows: Collection<List<Pair<TextView, TextPlate>>>) {
        val density = root.resources.displayMetrics.density.toDouble()
        for (row in rows) {
            val cells = row.map { it.first.parent as View }
            val grid = cells.first().parent as? ViewGroup ?: continue
            val all = (0 until grid.childCount).map(grid::getChildAt).filter { it.visibility == View.VISIBLE }
            val gridX = inRoot(root, grid, RectF()).left
            val left = (gridX + all.minOf { it.left }) / density
            val right = (gridX + all.maxOf { it.right }) / density
            val cellX = cells.map { inRoot(root, it, RectF()).left }
            val centres = cells.mapIndexed { i, c -> (cellX[i] + c.width / 2f) / density }
            val plates = LabelRows.plates(
                centres, row.map { it.second.textWidth() / density }, cells.first().width / density, left, right,
            )
            row.forEachIndexed { i, (view, plate) ->
                val viewX = inRoot(root, view, RectF()).left
                plate.rowPlate = (plates[i].x * density - viewX).toFloat() to (plates[i].w * density).toFloat()
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
