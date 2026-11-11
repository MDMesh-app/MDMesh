package com.mdmesh.agent.ui

/** How the image covers the launcher's window. */
enum class BackdropFit {
    /** Covers the window, cropped around the focal point (today's only mode: centre). */
    FILL,

    /** The whole image, letterboxed with the kiosk background colour. */
    FIT,
}

/** The point of the image (0..1 each way) that a [BackdropFit.FILL] crop keeps in view; the centre by default. */
data class Focal(val x: Double = CENTRE, val y: Double = CENTRE) {
    companion object {
        private const val CENTRE = 0.5
        val CENTRE_POINT = Focal()
    }
}

/**
 * Where the image sits in the window: window point (0, 0) is image point ([left], [top]), and one window unit is
 * [perUnit] image pixels. For [BackdropFit.FIT] [left] or [top] is negative: the letterbox.
 */
data class Placement(val left: Double, val top: Double, val perUnit: Double)

/**
 * The one place the backdrop's geometry is worked out, for drawing, decoding and the legibility cells alike, in
 * Double throughout. Servers without a fit mode or focal point mean FILL around the centre.
 */
object BackdropGeometry {

    fun place(
        image: Pair<Int, Int>,
        window: Pair<Double, Double>,
        fit: BackdropFit = BackdropFit.FILL,
        focal: Focal = Focal.CENTRE_POINT,
    ): Placement {
        val (iw, ih) = image.first.toDouble() to image.second.toDouble()
        val (w, h) = window
        return when (fit) {
            BackdropFit.FILL -> {
                val scale = maxOf(w / iw, h / ih)
                val cw = minOf(iw, w / scale)
                val ch = minOf(ih, h / scale)
                Placement(
                    left = (focal.x * iw - cw / 2).coerceIn(0.0, iw - cw),
                    top = (focal.y * ih - ch / 2).coerceIn(0.0, ih - ch),
                    perUnit = cw / w,
                )
            }
            BackdropFit.FIT -> {
                val perUnit = 1 / minOf(w / iw, h / ih)
                Placement(left = (iw - w * perUnit) / 2, top = (ih - h * perUnit) / 2, perUnit = perUnit)
            }
        }
    }
}
