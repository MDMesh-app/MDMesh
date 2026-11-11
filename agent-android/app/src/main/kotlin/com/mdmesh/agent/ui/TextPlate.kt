package com.mdmesh.agent.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
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

    /**
     * [root] is laid out over the image described by [info], under the scrim at [scrimStep] of [background]; null
     * [info]: no image, no plates.
     */
    fun update(root: ViewGroup, info: BackdropInfo?, background: Int, scrimStep: Int) {
        val plates = mutableListOf<Pair<TextView, TextPlate>>()
        collect(root, plates)
        if (plates.isEmpty() || root.width == 0 || root.height == 0) return
        val origin = IntArray(2).also(root::getLocationInWindow)
        val at = IntArray(2)
        fun top(v: View): Int = at.also(v::getLocationInWindow)[1]
        plates.filter { it.second.inRow && it.first.isShown }
            .groupBy { top(it.first) }
            .values.forEach { row ->
                val width = row.maxOf { it.second.naturalWidth() }
                row.forEach { it.second.sharedWidth = width }
            }
        val rect = RectF()
        for ((view, plate) in plates) {
            if (info == null) {
                plate.step = 0
                continue
            }
            plate.plateRect(rect)
            view.getLocationInWindow(at)
            val x = (at[0] - origin[0]).toFloat()
            val y = (at[1] - origin[1]).toFloat()
            val empty = rect.isEmpty
            rect.offset(x, y)
            val box = info.box(root.width, root.height, rect)
            plate.step = if (empty) 0 else KioskScrim.plateStep(view.currentTextColor, background, box, scrimStep)
        }
    }

    private fun collect(v: View, into: MutableList<Pair<TextView, TextPlate>>) {
        if (v is TextView) (v.background as? TextPlate)?.let { into += v to it }
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), into)
    }

    /** The plate's corner radius for [density]: the console's small radius (`--radius-sm`, 10px). */
    fun radius(density: Float): Float = (RADIUS_DP * density).roundToInt().toFloat()

    const val RADIUS_DP = 10
}
