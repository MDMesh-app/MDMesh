package com.mdmesh.agent.ui

/**
 * The adaptive scrim, exactly as `docs/kiosk-background-scrim.md` defines it (the console's preview runs the same
 * function; both pass `proto/vectors/kiosk-scrim.json`): the kiosk background colour laid over the image at the
 * lowest of 51 integer steps at which the text and secondary text colours reach 4.5:1 over all but 2 % of the
 * image's samples. Calm images stay vivid (step 0); a busy one is dimmed just enough. At step 50 every sample is the
 * background colour, which the palette already proves readable, so an answer always exists. Pure Kotlin.
 *
 * Text plates ([plateStep]) cover what the 2 % allowance and the sample averages leave out: the real pixels under
 * one piece of text.
 */
object KioskScrim {

    /** Steps of the scrim's opacity: step / [STEPS]. */
    const val STEPS = 50

    /** [px] seen through [scrim] at [step]: per channel `floor((scrim * step + px * (50 - step) + 25) / 50)`. */
    fun blend(scrim: Int, px: Int, step: Int): Int {
        fun mix(shift: Int): Int {
            val s = (scrim shr shift) and BYTE
            val p = (px shr shift) and BYTE
            return ((s * step + p * (STEPS - step) + HALF_STEPS) / STEPS) shl shift
        }
        return OPAQUE or mix(RED) or mix(GREEN) or mix(BLUE)
    }

    /** How many of [n] samples may stay under 4.5:1: `floor(n * 2 / 100)`. */
    fun allowed(n: Int): Int = n * ALLOWANCE_PERCENT / PERCENT

    /**
     * The contrast [colour] reaches over the [samples] seen through [scrim] at [step], all but the allowance: the
     * (allowed + 1)-th lowest. Null without samples.
     */
    fun covered(colour: Int, scrim: Int, samples: IntArray, step: Int): Double? {
        if (samples.isEmpty()) return null
        val ratios = DoubleArray(samples.size) { Contrast.ratio(colour, blend(scrim, samples[it], step)) }
        ratios.sort()
        return ratios[allowed(samples.size)]
    }

    /** The scrim step (0..[STEPS]) for [samples]: opaque colours, already composited over the background. */
    fun step(palette: KioskPalette, samples: IntArray): Int =
        (0..STEPS).firstOrNull { step ->
            listOf(palette.text, palette.secondary).all { colour ->
                (covered(colour, palette.background, samples, step) ?: Double.MAX_VALUE) >= Contrast.TEXT_MIN
            }
        } ?: STEPS

    /** The scrim's opacity, 0..1. */
    fun alpha(step: Int): Float = step / STEPS.toFloat()

    /**
     * The plate (0..[STEPS], 0: none) one text in [colour] needs over the pixels within [box] (raw colours,
     * composited over the [background]), seen through the scrim at [scrimStep]:
     * - solid when the patch is busy (through the scrim, some colour channel still spans more than [BUSY_LEVELS] of
     *   255 levels): texture behind letters hurts reading even at 4.5:1, and no checker can judge text over it;
     * - otherwise none when every pixel already reaches 4.5:1 through the scrim alone;
     * - otherwise the least opacity at which every one of them reaches 4.5:1 through scrim and plate.
     * Luminance grows with every channel, so the box's two corners bound the luminance of every colour inside it.
     * Each corner is widened by one level, for the canvas's rounding of the drawn alphas.
     */
    fun plateStep(colour: Int, background: Int, box: ColourBox, scrimStep: Int): Int {
        val low = blend(background, box.min, scrimStep)
        val high = blend(background, box.max, scrimStep)
        fun reads(plate: Int) = worst(
            colour,
            nudge(blend(background, low, plate), -1),
            nudge(blend(background, high, plate), 1),
        ) >= Contrast.TEXT_MIN
        val span = listOf(RED, GREEN, BLUE).maxOf { ((high shr it) and BYTE) - ((low shr it) and BYTE) }
        return when {
            span > BUSY_LEVELS -> STEPS
            reads(0) -> 0
            else -> (1..STEPS).firstOrNull(::reads) ?: STEPS
        }
    }

    /** A patch with a colour channel spanning more than this many levels (of 255) through the scrim is busy. */
    const val BUSY_LEVELS = 48

    /** The lowest contrast [colour] has with any colour between [low] and [high] (per channel). */
    private fun worst(colour: Int, low: Int, high: Int): Double {
        val lc = Contrast.luminance(colour)
        return when {
            lc < Contrast.luminance(low) -> Contrast.ratio(colour, low)
            lc > Contrast.luminance(high) -> Contrast.ratio(colour, high)
            else -> 1.0
        }
    }

    private fun nudge(c: Int, by: Int): Int {
        fun ch(shift: Int) = (((c shr shift) and BYTE) + by).coerceIn(0, BYTE) shl shift
        return OPAQUE or ch(RED) or ch(GREEN) or ch(BLUE)
    }

    /** [argb] (any alpha, not premultiplied) laid over the opaque [background]. */
    fun over(argb: Int, background: Int): Int {
        val a = (argb ushr ALPHA) and BYTE
        if (a == BYTE) return argb
        fun mix(shift: Int): Int {
            val f = (argb shr shift) and BYTE
            val b = (background shr shift) and BYTE
            return ((f * a + b * (BYTE - a) + BYTE / 2) / BYTE) shl shift
        }
        return OPAQUE or mix(RED) or mix(GREEN) or mix(BLUE)
    }

    private const val ALLOWANCE_PERCENT = 2
    private const val PERCENT = 100
    private const val HALF_STEPS = STEPS / 2
    private const val OPAQUE = 0xFF000000.toInt()
    private const val BYTE = 0xFF
    private const val ALPHA = 24
    private const val RED = 16
    private const val GREEN = 8
    private const val BLUE = 0
}

/** The per-channel range of some opaque colours: [min] holds each channel's lowest value, [max] each highest. */
data class ColourBox(val min: Int, val max: Int) {

    operator fun plus(other: ColourBox): ColourBox =
        ColourBox(channels(min, other.min, ::minOf), channels(max, other.max, ::maxOf))

    /** Grown to hold [colour]. */
    operator fun plus(colour: Int): ColourBox = plus(ColourBox(colour, colour))

    companion object {
        /** Holds nothing: plus any colour gives that colour. */
        val EMPTY = ColourBox(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
    }
}

private fun channels(a: Int, b: Int, pick: (Int, Int) -> Int): Int {
    var out = BOX_OPAQUE
    for (shift in BOX_SHIFTS) out = out or (pick((a shr shift) and BOX_BYTE, (b shr shift) and BOX_BYTE) shl shift)
    return out
}

private const val BOX_OPAQUE = 0xFF000000.toInt()
private const val BOX_BYTE = 0xFF
private const val BOX_RED = 16
private const val BOX_GREEN = 8
private val BOX_SHIFTS = intArrayOf(BOX_RED, BOX_GREEN, 0)
