package com.mdmesh.agent.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.app.Activity
import android.view.View

/**
 * The kiosk backdrop: the background colour, the image centre-cropped to the bounds, then the colour again as a
 * dim at [dim] steps of 50 ([BackdropRule]). Set as the launcher root's background, it runs under the system bars and
 * the cutout (a background ignores padding), survives the renderer's rebuilds, and just re-crops for new bounds.
 */
class BackdropDrawable(
    private val color: Int,
    val bitmap: Bitmap,
    dim: Int = 0,
) : Drawable() {

    /** The dim, 0..50 steps of the colour over the image: worked out after layout, from the controls' boxes. */
    var dim: Int = dim
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val matrix = Matrix()

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(color or OPAQUE)
        if (b.width() > 0 && b.height() > 0) {
            val scale = maxOf(b.width() / bitmap.width.toFloat(), b.height() / bitmap.height.toFloat())
            matrix.setScale(scale, scale)
            matrix.postTranslate(
                b.left + (b.width() - bitmap.width * scale) / 2f,
                b.top + (b.height() - bitmap.height * scale) / 2f,
            )
            canvas.save()
            canvas.clipRect(b)
            canvas.drawBitmap(bitmap, matrix, paint)
            canvas.restore()
        }
        val scrim = (dim.coerceIn(0, KioskScrim.STEPS) * BYTE + KioskScrim.STEPS / 2) / KioskScrim.STEPS
        if (scrim > 0) canvas.drawColor((scrim shl ALPHA_SHIFT) or (color and RGB))
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
        const val RGB = 0x00FFFFFF
        const val BYTE = 255
        const val ALPHA_SHIFT = 24
    }
}

/**
 * Paints the kiosk launcher's [root] for [screen]: the kiosk colour, or the image (its dim follows after layout,
 * [TextPlates]). As the root's background it runs under the system bars (padding doesn't apply to a background) and
 * stays through rebuilds. Before Android 15 the bars take the kiosk colour. Returns whether the image is drawn (not
 * for none, the idle screen, or [info] made for another background colour: a new decode for it is on its way).
 */
internal fun Activity.paintKioskBackground(
    root: View,
    screen: KioskScreen,
    bitmap: Bitmap?,
    info: BackdropInfo?,
): Boolean {
    val palette = screen.palette
    colorSystemBars(palette.background)
    val usable = info?.takeIf { screen != KioskScreen.Idle && it.background == palette.background }
    if (bitmap == null || usable == null) {
        root.background = ColorDrawable(palette.background)
        updateSystemBarIcons(palette.background)
        return false
    }
    val previous = (root.background as? BackdropDrawable)?.dim ?: 0
    root.background = BackdropDrawable(palette.background, bitmap, previous)
    updateSystemBarIcons(KioskScrim.blend(palette.background, usable.top, previous))
    return true
}
