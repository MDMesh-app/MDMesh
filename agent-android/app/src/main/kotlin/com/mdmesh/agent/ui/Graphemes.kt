package com.mdmesh.agent.ui

import android.icu.text.BreakIterator
import java.util.Locale

/**
 * Grapheme cluster boundaries from the platform's ICU (`android.icu`, API 24 and up), with the spec's guards
 * ([GraphemeGuards]): Android 7 ships Unicode 8 data, which splits emoji ZWJ sequences and skin tones and glues
 * every regional indicator together; the guards give the same cuts there as on current Unicode.
 */
object IcuGraphemes : Graphemes {
    override fun ends(s: String): IntArray {
        if (s.isEmpty()) return IntArray(0)
        val it = BreakIterator.getCharacterInstance(Locale.ROOT)
        it.setText(s)
        val ends = mutableListOf<Int>()
        var b = it.next()
        while (b != BreakIterator.DONE) {
            ends += b
            b = it.next()
        }
        return GraphemeGuards.apply(s, ends.toIntArray())
    }
}

/**
 * The spec's rules on top of UAX #29 cluster ends (`docs/kiosk-background-scrim.md`, Label lines, 3.4): no cut right
 * after U+200D; none before a combining mark, U+200D, U+FE0F, a skin tone (U+1F3FB..1F3FF) or a tag
 * (U+E0020..E007F); in a run of regional indicators, a cut after every second one and nowhere else. On current
 * Unicode data these change nothing but a ZWJ before a non-emoji; on older data they mend what changed since.
 */
object GraphemeGuards {
    fun apply(s: String, clusterEnds: IntArray): IntArray {
        val out = sortedSetOf<Int>()
        for (e in clusterEnds) if (allowed(s, e)) out += e
        // Regional indicators pair from the start of their run: inside the run, a cut after each pair and nowhere else.
        var i = 0
        var run = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            run = if (isRegional(cp)) run + 1 else 0
            val inRun = run > 0 && i < s.length && isRegional(s.codePointAt(i))
            if (inRun && run % 2 == 0) out += i
            if (inRun && run % 2 == 1) out -= i
        }
        out += s.length
        return out.toIntArray()
    }

    private fun allowed(s: String, e: Int): Boolean = when {
        e >= s.length -> true
        e <= 0 || Character.isLowSurrogate(s[e]) && Character.isHighSurrogate(s[e - 1]) -> false
        else -> s.codePointBefore(e) != ZWJ && !joinsPrevious(s.codePointAt(e))
    }

    private fun joinsPrevious(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> cp == ZWJ || cp == VS16 || cp in SKIN_TONES || cp in TAGS
    }

    private fun isRegional(cp: Int) = cp in REGIONAL

    private const val ZWJ = 0x200D
    private const val VS16 = 0xFE0F
    private const val SKIN_FIRST = 0x1F3FB
    private const val SKIN_LAST = 0x1F3FF
    private const val TAG_FIRST = 0xE0020
    private const val TAG_LAST = 0xE007F
    private const val REGIONAL_FIRST = 0x1F1E6
    private const val REGIONAL_LAST = 0x1F1FF
    private val SKIN_TONES = SKIN_FIRST..SKIN_LAST
    private val TAGS = TAG_FIRST..TAG_LAST
    private val REGIONAL = REGIONAL_FIRST..REGIONAL_LAST
}
