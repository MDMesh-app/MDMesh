package com.mdmesh.agent.ui

/**
 * The kiosk launcher's colours, derived from the admin's kiosk background and text colours (`0xAARRGGBB`). Pure
 * Kotlin, so it is unit-tested on the JVM. The launcher draws only with these, never with the app theme, so it
 * looks the same in light and dark mode and needs no redraw when the system switches.
 *
 * Readability floors (WCAG, via [Contrast]):
 * - [text] is the admin's text colour while it reaches 4.5:1 on the background (WCAG AA for text). The launcher has
 *   no large text (its titles are 20sp medium, not 24sp or bold), so the 3:1 large-text level never applies. Below
 *   4.5:1 the v2 text colour that reads best on the background is used instead, for all of the launcher's text.
 * - [secondary] is [text] at 80% over the background, but never under 4.5:1: then it is [text].
 * - The filled button is "ink = text, on-ink = background": its label reaches 4.5:1 and its edge 3:1, and so does
 *   the [focus] ring.
 */
data class KioskPalette(
    val background: Int,
    val text: Int,
    val secondary: Int,
    /** Decorative 1dp lines (the empty-state frame). */
    val line: Int,
    /** Pressed cells and outlined buttons: [text] at 12% (focus also gets the [focus] ring). */
    val ripple: Int,
    val buttonFill: Int,
    val buttonLabel: Int,
    /** Pressed filled button: [buttonLabel] at 24%. */
    val buttonRipple: Int,
) {
    /** The 2dp keyboard and D-pad focus ring around cells and buttons: the text colour. */
    val focus: Int get() = buttonFill

    companion object {
        /** v2 dark `--app`: the launcher's background when the kiosk sets none. */
        const val DEFAULT_BACKGROUND = 0xFF0F1115.toInt()

        /** v2 dark `--text`: the launcher's text colour when the kiosk sets none. */
        const val DEFAULT_TEXT = 0xFFE7EBF1.toInt()

        /** v2 light `--text`, the dark-on-light fallback. */
        const val LIGHT_TEXT = 0xFF1B2230.toInt()

        /** The idle screen and kiosks without colours. */
        val DEFAULT: KioskPalette = of(null, null)

        fun of(background: Int?, text: Int?): KioskPalette {
            val bg = opaque(background ?: DEFAULT_BACKGROUND)
            val readable = mostReadableOn(bg)
            val fg = opaque(text ?: DEFAULT_TEXT).takeIf { Contrast.ratio(it, bg) >= Contrast.TEXT_MIN } ?: readable
            val secondary = blend(fg, bg, SECONDARY_ALPHA).takeIf { Contrast.ratio(it, bg) >= Contrast.TEXT_MIN }
                ?: fg
            return KioskPalette(
                background = bg,
                text = fg,
                secondary = secondary,
                line = blend(fg, bg, LINE_ALPHA),
                ripple = withAlpha(fg, RIPPLE_ALPHA),
                buttonFill = fg,
                buttonLabel = bg,
                buttonRipple = withAlpha(bg, BUTTON_RIPPLE_ALPHA),
            )
        }

        /**
         * A kiosk colour from the configuration, read as `docs/kiosk-background-scrim.md` (Parsing) says, the same
         * as the console: `#RRGGBB`, `#AARRGGBB` (the alpha is dropped: the launcher draws opaque colours) or one of
         * Android's colour names, any case, exactly as written. Anything else (`#RGB`, no `#`, spaces, `#+FFFFF`,
         * CSS forms) is null: the default colour. Never Color.parseColor on these untrusted strings; the names are
         * its table, fixed here so every Android version reads them alike.
         */
        fun parse(value: String?): Int? = when {
            value == null -> null
            HEX_COLOUR.matches(value) -> OPAQUE or (value.takeLast(RGB_DIGITS).toLong(HEX_RADIX).toInt() and RGB)
            else -> NAMED[value.lowercase(java.util.Locale.ROOT)]?.let { OPAQUE or it }
        }

        private val HEX_COLOUR = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")

        /** Color.parseColor's names. */
        private val NAMED: Map<String, Int> = mapOf(
            "black" to 0x000000, "darkgray" to 0x444444, "gray" to 0x888888, "lightgray" to 0xCCCCCC,
            "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x00FF00, "blue" to 0x0000FF, "yellow" to 0xFFFF00,
            "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "aqua" to 0x00FFFF, "fuchsia" to 0xFF00FF,
            "darkgrey" to 0x444444, "grey" to 0x888888, "lightgrey" to 0xCCCCCC, "lime" to 0x00FF00,
            "maroon" to 0x800000, "navy" to 0x000080, "olive" to 0x808000, "purple" to 0x800080,
            "silver" to 0xC0C0C0, "teal" to 0x008080,
        )
        private const val RGB_DIGITS = 6
        private const val HEX_RADIX = 16

        /** The v2 text colour that reads best on [bg], or black or white when neither reaches 4.5:1. */
        fun mostReadableOn(bg: Int): Int {
            val v2 = listOf(DEFAULT_TEXT, LIGHT_TEXT).maxBy { Contrast.ratio(it, bg) }
            if (Contrast.ratio(v2, bg) >= Contrast.TEXT_MIN) return v2
            // Black or white always reaches 4.58:1 on an opaque colour.
            return listOf(WHITE, BLACK).maxBy { Contrast.ratio(it, bg) }
        }

        /** [fg] laid over [bg] at [alpha] (0..1), as an opaque colour. */
        fun blend(fg: Int, bg: Int, alpha: Float): Int {
            fun mix(shift: Int): Int {
                val f = (fg shr shift) and BYTE
                val b = (bg shr shift) and BYTE
                return (Math.round(f * alpha + b * (1 - alpha)) and BYTE) shl shift
            }
            return OPAQUE or mix(RED) or mix(GREEN) or mix(BLUE)
        }

        private fun opaque(c: Int): Int = c or OPAQUE

        private fun withAlpha(c: Int, alpha: Float): Int = (Math.round(alpha * BYTE) shl ALPHA) or (c and RGB)

        private const val SECONDARY_ALPHA = 0.8f
        private const val LINE_ALPHA = 0.24f
        private const val RIPPLE_ALPHA = 0.12f
        private const val BUTTON_RIPPLE_ALPHA = 0.24f
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val OPAQUE = 0xFF000000.toInt()
        private const val RGB = 0x00FFFFFF
        private const val BYTE = 0xFF
        private const val ALPHA = 24
        private const val RED = 16
        private const val GREEN = 8
        private const val BLUE = 0
    }
}
