package com.mdmesh.agent.ui

/**
 * What the launcher needs to know about the visible part of a background image, without keeping its pixels: the
 * dark and light ends of its colours (2nd and 98th luminance percentile of a downsampled crop) and a typical colour
 * of the strip under the status bar.
 */
data class BackdropStats(val darkest: Int, val lightest: Int, val top: Int)

/**
 * The adaptive scrim: the kiosk background colour laid over the image at the least opacity that keeps every text
 * and button colour of the [KioskPalette] at 4.5:1 (and so edges and focus rings above 3:1) against both ends of the
 * image's colours. Calm images stay vivid (no scrim); a busy one is dimmed just enough. At full opacity the
 * backdrop is the plain colour, which the palette already proves readable, so an answer always exists. Pure Kotlin:
 * the console computes the same function for its preview, against the same test vectors.
 */
object KioskScrim {

    /** The opacity (0..1, in steps of [STEP]) of the background colour over the image. */
    fun alpha(palette: KioskPalette, stats: BackdropStats): Float {
        var step = 0
        while (step < STEPS) {
            val a = step / STEPS.toFloat()
            if (readable(palette, stats, a)) return a
            step++
        }
        return 1f
    }

    /** Whether every colour the launcher draws over the image reaches 4.5:1 with the scrim at [alpha]. */
    fun readable(palette: KioskPalette, stats: BackdropStats, alpha: Float): Boolean =
        listOf(stats.darkest, stats.lightest).all { px ->
            val under = scrimmed(palette, px, alpha)
            listOf(palette.text, palette.secondary, palette.buttonFill).all { Contrast.ratio(it, under) >= MIN }
        }

    /** [pixel] seen through the scrim at [alpha]. */
    fun scrimmed(palette: KioskPalette, pixel: Int, alpha: Float): Int =
        KioskPalette.blend(palette.background, pixel, alpha)

    /** [BackdropStats] from opaque ARGB [pixels] of a crop [width] wide, the first [topRows] rows under the bar. */
    fun stats(pixels: IntArray, width: Int, topRows: Int): BackdropStats {
        require(pixels.isNotEmpty() && width > 0)
        val sorted = pixels.sortedBy { Contrast.luminance(it) }
        val low = sorted[((sorted.size - 1) * LOW_PERCENTILE).toInt()]
        val high = sorted[((sorted.size - 1) * HIGH_PERCENTILE).toInt()]
        val strip = pixels.copyOfRange(0, (topRows.coerceAtLeast(1) * width).coerceAtMost(pixels.size))
        val top = strip.sortedBy { Contrast.luminance(it) }[strip.size / 2]
        return BackdropStats(low or OPAQUE, high or OPAQUE, top or OPAQUE)
    }

    private const val MIN = Contrast.TEXT_MIN
    /** 2 % steps: 50 levels. */
    private const val STEPS = 50
    private const val LOW_PERCENTILE = 0.02
    private const val HIGH_PERCENTILE = 0.98
    private const val OPAQUE = 0xFF000000.toInt()
}
