package com.mdmesh.agent.ui

import com.mdmesh.proto.KioskBackgroundImageDto

/** How the image covers the launcher's window (`docs/kiosk-background-scrim.md`, The window). */
enum class BackdropFit {
    /** Covers the window, cropped around the focal point. The default. */
    FILL,

    /** The whole image, centred, letterboxed with the kiosk background colour; the focal point is ignored. */
    FIT,
}

/** The point of the image (0..1 each way) that a [BackdropFit.FILL] crop keeps in view. */
data class Focal(val x: Double, val y: Double) {
    companion object {
        private const val CENTRE = 0.5

        /** The centre: Android's CENTER_CROP. */
        val CENTRE_POINT = Focal(CENTRE, CENTRE)
    }
}

/** The image's rectangle in the window ([x], [y], [w], [h]), and [scale], window units per image pixel. */
data class ImageRect(val x: Double, val y: Double, val w: Double, val h: Double, val scale: Double)

/** A configuration's fit and focal point; servers without them (before protocol 1.10) mean fill around the centre. */
data class Placement(val fit: BackdropFit, val focal: Focal) {
    companion object {
        val DEFAULT = Placement(BackdropFit.FILL, Focal.CENTRE_POINT)

        fun of(image: KioskBackgroundImageDto?): Placement = when {
            image == null -> DEFAULT
            image.letterboxed -> Placement(BackdropFit.FIT, Focal.CENTRE_POINT)
            else -> Placement(BackdropFit.FILL, Focal(image.focalXOrCentre, image.focalYOrCentre))
        }
    }
}

/** The one place the backdrop's geometry is worked out, for drawing and the legibility cells alike; Double only. */
object BackdropGeometry {

    /** Where an [iw] x [ih] image lies in a [w] x [h] window, by [placement]. */
    fun rect(iw: Int, ih: Int, w: Double, h: Double, placement: Placement): ImageRect = when (placement.fit) {
        BackdropFit.FILL -> {
            val scale = maxOf(w / iw, h / ih)
            val cw = w / scale
            val ch = h / scale
            val cx = clamp(placement.focal.x * iw - cw / 2, 0.0, iw - cw)
            val cy = clamp(placement.focal.y * ih - ch / 2, 0.0, ih - ch)
            ImageRect(-cx * scale, -cy * scale, iw * scale, ih * scale, scale)
        }
        BackdropFit.FIT -> {
            val scale = minOf(w / iw, h / ih)
            val rw = iw * scale
            val rh = ih * scale
            ImageRect((w - rw) / 2, (h - rh) / 2, rw, rh, scale)
        }
    }

    /** `max(a, min(b, v))`: when b < a (no room), a. */
    private fun clamp(v: Double, a: Double, b: Double) = maxOf(a, minOf(b, v))
}

/** A plate of a grid row's label: [x] and [w] in the row's coordinates. */
data class RowPlate(val x: Double, val w: Double)

/** The label plates of one grid row (`docs/kiosk-background-scrim.md`, Label rows). */
object LabelRows {
    /** Plate padding either side of the text, dp. */
    const val PAD_DP = 8

    /** A row plate is at most the cell less this, dp, so neighbours stay apart. */
    const val GAP_DP = 8

    /**
     * Plates for labels whose icons are centred at [centres] with laid-out text widths [textWidths], in cells
     * [cellWidth] wide between the grid's content edges [left] and [right]: one width, the widest text plus padding but
     * at most the cell less [GAP_DP]; each centred under its icon, then kept inside the content edges.
     */
    fun plates(
        centres: List<Double>,
        textWidths: List<Double>,
        cellWidth: Double,
        left: Double,
        right: Double,
    ): List<RowPlate> {
        val width = minOf((textWidths.maxOrNull() ?: 0.0) + 2 * PAD_DP, cellWidth - GAP_DP)
        return centres.map { c -> RowPlate(minOf(maxOf(c - width / 2, left), right - width), width) }
    }

    /** The width a label's text may take in a cell: the row plate's maximum less its padding. */
    fun textBox(cellWidth: Double): Double = cellWidth - GAP_DP - 2 * PAD_DP
}
