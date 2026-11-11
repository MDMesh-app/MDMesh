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
 * It hugs the text ([padH] either side, the view's full height) and is centred on it; [sharedWidth] widens it to
 * match the other labels of its grid row (never past the view). Its opacity ([step] of [KioskScrim.STEPS], 0: not
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

    var sharedWidth: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** The text's own width plus [padH] either side; 0 before layout or without text. */
    fun naturalWidth(): Int {
        val layout = view.layout?.takeIf { it.lineCount > 0 && !view.text.isNullOrEmpty() } ?: return 0
        var w = 0f
        for (i in 0 until layout.lineCount) w = maxOf(w, layout.getLineRight(i) - layout.getLineLeft(i))
        return ceil(w).toInt() + 2 * padH
    }

    /** Where the plate is, in the view's own coordinates; empty when there is nothing to back. */
    fun plateRect(out: RectF): RectF {
        val layout = view.layout
        val natural = naturalWidth()
        if (layout == null || natural == 0) return out.apply { setEmpty() }
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        for (i in 0 until layout.lineCount) {
            left = minOf(left, layout.getLineLeft(i))
            right = maxOf(right, layout.getLineRight(i))
        }
        val centre = view.compoundPaddingLeft + (left + right) / 2f
        val width = minOf(view.width, maxOf(natural, sharedWidth)).toFloat()
        val l = (centre - width / 2f).coerceIn(0f, maxOf(0f, view.width - width))
        return out.apply { set(l, 0f, l + width, view.height.toFloat()) }
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

    /**
     * [root] is laid out over the image described by [info] (null: no image, no plates), drawn by the root's
     * [BackdropDrawable] in [palette]. Sets the row widths, the dim from the controls, then every plate's opacity.
     * Returns the dim, or null without an image.
     */
    fun update(root: ViewGroup, info: BackdropInfo?, palette: KioskPalette): Int? {
        val plates = mutableListOf<Pair<TextView, TextPlate>>()
        val controls = mutableListOf<View>()
        collect(root, plates, controls)
        shareRowWidths(plates)
        if (info == null || root.width == 0 || root.height == 0) {
            plates.forEach { it.second.step = 0 }
            return null
        }
        val origin = IntArray(2).also(root::getLocationInWindow)
        val rect = RectF()
        fun bounds(v: View, r: RectF): ColourBox {
            val at = IntArray(2).also(v::getLocationInWindow)
            r.offset((at[0] - origin[0]).toFloat(), (at[1] - origin[1]).toFloat())
            val region = BackdropCells.Region(
                r.left.toDouble(), r.top.toDouble(), r.width().toDouble(), r.height().toDouble(),
            )
            return info.cells.bounds(root.width.toDouble(), root.height.toDouble(), region)
        }
        val rule = BackdropRules.current
        val controlBounds = controls.filter { it.isShown }
            .map { bounds(it, RectF(0f, 0f, it.width.toFloat(), it.height.toFloat())) }
        val dim = rule.dimStep(palette, controlBounds)
        if (rule is LegibilityV3 && rule.controlsShort(palette, controlBounds, dim)) {
            Log.w(TAG, "kiosk background: the Exit button stays under 3:1 even at the 30% dim")
        }
        (root.background as? BackdropDrawable)?.dim = dim
        for ((view, plate) in plates) {
            plate.plateRect(rect)
            plate.step = if (rect.isEmpty || !view.isShown) {
                0
            } else {
                rule.plateStep(view.currentTextColor, palette.background, bounds(view, rect), dim)
            }
        }
        // The labels of a grid row are plated alike: the row's step for each of them.
        rows(plates).forEach { row ->
            val shared = rule.rowStep(row.map { it.second.step })
            row.forEach { it.second.step = shared }
        }
        return dim
    }

    /** The shown grid labels, by row (labels of a row share their top). */
    private fun rows(plates: List<Pair<TextView, TextPlate>>): Collection<List<Pair<TextView, TextPlate>>> {
        val at = IntArray(2)
        return plates.filter { it.second.inRow && it.first.isShown }
            .groupBy { at.also(it.first::getLocationInWindow)[1] }
            .values
    }

    private fun shareRowWidths(plates: List<Pair<TextView, TextPlate>>) {
        rows(plates).forEach { row ->
            val width = row.maxOf { it.second.naturalWidth() }
            row.forEach { it.second.sharedWidth = width }
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
