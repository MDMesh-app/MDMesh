package com.mdmesh.agent.ui

/**
 * The integer colour arithmetic of the kiosk background's legibility rules, exactly as
 * `docs/kiosk-background-scrim.md` (version 3) defines it, so the console's preview and the device agree to the
 * step. Pure Kotlin; `proto/vectors/kiosk-scrim.json` checks it.
 */
object KioskScrim {

    /** Steps of an opacity: step / [STEPS]. */
    const val STEPS = 50

    /** A patch with a colour channel spanning more than this many levels (of 255) is busy: texture. */
    const val BUSY_LEVELS = 48

    /** [px] under [c] at [step]: per channel `floor((c * step + px * (50 - step) + 25) / 50)`. */
    fun blend(c: Int, px: Int, step: Int): Int {
        fun mix(shift: Int): Int {
            val s = (c shr shift) and BYTE
            val p = (px shr shift) and BYTE
            return ((s * step + p * (STEPS - step) + HALF_STEPS) / STEPS) shl shift
        }
        return OPAQUE or mix(RED) or mix(GREEN) or mix(BLUE)
    }

    /** An opacity step as 0..1. */
    fun alpha(step: Int): Float = step / STEPS.toFloat()

    /**
     * The lowest contrast [colour] has against any colour between [lo] and [hi] (per channel), each widened by one
     * level for the GPU's rounding. Luminance grows with every channel, so the two corners bound everything between.
     */
    fun worst(colour: Int, lo: Int, hi: Int): Double {
        val a = Contrast.luminance(nudge(lo, -1))
        val b = Contrast.luminance(nudge(hi, 1))
        val t = Contrast.luminance(colour)
        return when {
            t < a -> (a + OFFSET) / (t + OFFSET)
            t > b -> (t + OFFSET) / (b + OFFSET)
            else -> 1.0
        }
    }

    /** Some channel spans more than [BUSY_LEVELS] from [lo] to [hi]. */
    fun busy(lo: Int, hi: Int): Boolean =
        listOf(RED, GREEN, BLUE).any { ((hi shr it) and BYTE) - ((lo shr it) and BYTE) > BUSY_LEVELS }

    /** [argb] (any alpha, not premultiplied) over the opaque [background]: `floor((c*a + b*(255-a) + 127) / 255)`. */
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

    private fun nudge(c: Int, by: Int): Int {
        fun ch(shift: Int) = (((c shr shift) and BYTE) + by).coerceIn(0, BYTE) shl shift
        return OPAQUE or ch(RED) or ch(GREEN) or ch(BLUE)
    }

    private const val HALF_STEPS = STEPS / 2
    private const val OFFSET = 0.05
    private const val OPAQUE = 0xFF000000.toInt()
    private const val BYTE = 0xFF
    private const val ALPHA = 24
    private const val RED = 16
    private const val GREEN = 8
    private const val BLUE = 0
}

/**
 * How the launcher stays legible over a background image: a dim of the background colour over all of it, then a
 * plate behind each text that needs one. The shared spec says which rule; [BackdropRules.current] is the one drawn,
 * and swapping it changes nothing else.
 */
interface BackdropRule {
    /** The dim (0..[KioskScrim.STEPS]) over the whole image, from the bounds under each control (none: no dim). */
    fun dimStep(palette: KioskPalette, controls: List<ColourBox>): Int

    /** The plate (0..[KioskScrim.STEPS], 0: none) behind one text in [colour] over [box], under the dim [dim]. */
    fun plateStep(colour: Int, background: Int, box: ColourBox, dim: Int): Int

    /** The one step the labels of a grid row share, from each label's own [steps]. */
    fun rowStep(steps: List<Int>): Int
}

/**
 * Version 3.1: a light dim, at most 30 %, only as much as the controls need for 3:1 (the outlined Exit button: its
 * border, in the text colour, against the dimmed image); then each text gets a plate: none where it already reads
 * 4.5:1 against its worst pixel, solid over texture, else the lowest opacity that reaches 4.5:1. The labels of a grid
 * row share one step: the highest of the row's, so a row is plated alike or not at all.
 */
object LegibilityV3 : BackdropRule {
    /** 15 of 50: 30 %. */
    const val DIM_MAX_STEP = 15

    override fun dimStep(palette: KioskPalette, controls: List<ColourBox>): Int {
        if (controls.isEmpty()) return 0
        val bg = palette.background
        return (0..DIM_MAX_STEP).firstOrNull { s ->
            controls.all {
                KioskScrim.worst(palette.text, KioskScrim.blend(bg, it.min, s), KioskScrim.blend(bg, it.max, s)) >=
                    Contrast.UI_MIN
            }
        } ?: DIM_MAX_STEP
    }

    override fun plateStep(colour: Int, background: Int, box: ColourBox, dim: Int): Int {
        val lo = KioskScrim.blend(background, box.min, dim)
        val hi = KioskScrim.blend(background, box.max, dim)
        fun reads(s: Int) =
            KioskScrim.worst(colour, KioskScrim.blend(background, lo, s), KioskScrim.blend(background, hi, s)) >=
                Contrast.TEXT_MIN
        return when {
            reads(0) -> 0
            KioskScrim.busy(lo, hi) -> KioskScrim.STEPS
            else -> (1..KioskScrim.STEPS).firstOrNull(::reads) ?: KioskScrim.STEPS
        }
    }

    override fun rowStep(steps: List<Int>): Int = steps.maxOrNull() ?: 0

    /** Whether the dim at its cap still leaves a control under 3:1 (the console warns the admin). */
    fun controlsShort(palette: KioskPalette, controls: List<ColourBox>, dim: Int): Boolean {
        val bg = palette.background
        return controls.any {
            KioskScrim.worst(palette.text, KioskScrim.blend(bg, it.min, dim), KioskScrim.blend(bg, it.max, dim)) <
                Contrast.UI_MIN
        }
    }
}

object BackdropRules {
    /** The rule the launcher draws with. */
    val current: BackdropRule = LegibilityV3
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
