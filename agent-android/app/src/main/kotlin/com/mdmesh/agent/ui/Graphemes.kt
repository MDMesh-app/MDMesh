package com.mdmesh.agent.ui

import android.icu.lang.UCharacter
import android.icu.lang.UCharacterDirection
import android.icu.text.BreakIterator
import java.util.Locale

/**
 * Grapheme cluster boundaries from the platform's ICU (`android.icu`, API 24 and up), with the spec's guards
 * ([GraphemeGuards]) on the same data: Android 7 ships Unicode 8, which splits emoji ZWJ sequences, skin tones, Indic
 * conjuncts and prepended marks and glues every regional indicator together; the guards make those cut as current
 * Unicode does.
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
        return GraphemeGuards.apply(s, ends.toIntArray(), UCharacter::getType)
    }
}

/**
 * The spec's rules on top of UAX #29 cluster ends (`docs/kiosk-background-scrim.md`, Label lines, 5): no cut right
 * after U+200D or a prepended concatenation mark; none before a combining mark, U+200D, U+FE0F, a skin tone or a tag;
 * none inside an Indic conjunct (consonant, linkers and extenders with a linker, consonant: Unicode 15.1's GB9c, with
 * the spec's fixed tables); between two regional indicators, a cut after every second one and nowhere else.
 * [typeOf] gives a code point's general category (`UCharacter.getType` on the device: the same data as its iterator).
 */
object GraphemeGuards {
    fun apply(s: String, clusterEnds: IntArray, typeOf: (Int) -> Int): IntArray {
        val out = sortedSetOf<Int>()
        for (e in clusterEnds) if (allowed(s, e, typeOf)) out += e
        // Regional indicators pair from the start of their run: between two, a cut after each pair and nowhere else.
        var i = 0
        var run = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            run = if (cp in REGIONAL) run + 1 else 0
            val between = run > 0 && i < s.length && s.codePointAt(i) in REGIONAL
            if (between && run % 2 == 0) out += i
            if (between && run % 2 == 1) out -= i
        }
        out += s.length
        return out.toIntArray()
    }

    private fun allowed(s: String, e: Int, typeOf: (Int) -> Int): Boolean = when {
        e >= s.length -> true
        e <= 0 || Character.isLowSurrogate(s[e]) && Character.isHighSurrogate(s[e - 1]) -> false
        else -> {
            val before = s.codePointBefore(e)
            before != ZWJ && before !in PREPENDED && !joinsPrevious(s.codePointAt(e), typeOf) &&
                !insideConjunct(s, e, typeOf)
        }
    }

    private fun joinsPrevious(cp: Int, typeOf: (Int) -> Int): Boolean = isMark(cp, typeOf) ||
        cp == ZWJ || cp == VS16 || cp in SKIN_TONES || cp in TAGS

    private fun isMark(cp: Int, typeOf: (Int) -> Int) = typeOf(cp).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() ||
            it == Character.COMBINING_SPACING_MARK.toInt()
    }

    /** At [e]: consonant (extender|linker)* linker (extender|linker)* × consonant. */
    private fun insideConjunct(s: String, e: Int, typeOf: (Int) -> Int): Boolean {
        var k = e
        var linked = false
        var start: Int? = null // the code point before the run of linkers and extenders (null: none yet)
        while (start == null && k > 0) {
            val cp = s.codePointBefore(k)
            val linker = cp in LINKERS
            val extender = cp == ZWJ || typeOf(cp).let {
                it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt()
            }
            if (linker || extender) {
                linked = linked || linker
                k -= Character.charCount(cp)
            } else {
                start = cp
            }
        }
        return linked && isConsonant(s.codePointAt(e)) && start != null && isConsonant(start)
    }

    private fun isConsonant(cp: Int) = CONSONANTS.any { cp in it }

    private const val ZWJ = 0x200D
    private const val VS16 = 0xFE0F
    @Suppress("MagicNumber") // code points
    private val SKIN_TONES = 0x1F3FB..0x1F3FF

    @Suppress("MagicNumber") // code points
    private val TAGS = 0xE0020..0xE007F

    @Suppress("MagicNumber") // code points
    private val REGIONAL = 0x1F1E6..0x1F1FF

    /** Prepended concatenation marks (GB9b), the spec's fixed list. */
    @Suppress("MagicNumber") // code points
    private val PREPENDED = setOf(
        0x0600, 0x0601, 0x0602, 0x0603, 0x0604, 0x0605, 0x06DD, 0x070F, 0x0890, 0x0891, 0x08E2, 0x110BD, 0x110CD,
    )

    /** Viramas that link conjuncts: Devanagari, Bengali, Gujarati, Oriya, Telugu, Malayalam. */
    @Suppress("MagicNumber") // code points
    private val LINKERS = setOf(0x094D, 0x09CD, 0x0ACD, 0x0B4D, 0x0C4D, 0x0D4D)

    /** Conjunct consonants of the same six scripts, the spec's fixed table. */
    @Suppress("MagicNumber") // code points
    private val CONSONANTS = listOf(
        0x0915..0x0939, 0x0958..0x095F, 0x0978..0x097F,
        0x0995..0x09A8, 0x09AA..0x09B0, 0x09B2..0x09B2, 0x09B6..0x09B9, 0x09DC..0x09DD, 0x09DF..0x09DF,
        0x09F0..0x09F1,
        0x0A95..0x0AA8, 0x0AAA..0x0AB0, 0x0AB2..0x0AB3, 0x0AB5..0x0AB9, 0x0AF9..0x0AF9,
        0x0B15..0x0B28, 0x0B2A..0x0B30, 0x0B32..0x0B33, 0x0B35..0x0B39, 0x0B5C..0x0B5D, 0x0B5F..0x0B5F,
        0x0B71..0x0B71,
        0x0C15..0x0C28, 0x0C2A..0x0C39, 0x0C58..0x0C5A,
        0x0D15..0x0D3A,
    )
}

/**
 * A label's one direction (`docs/kiosk-background-scrim.md`, Label lines, 6): UAX #9 rule P2 on the platform's own
 * Unicode data. Unassigned code points are skipped; an isolate initiator skips to its matching PDI (to the end when
 * none); a stray PDI is skipped; the first L is left to right, the first R or AL right to left; none: left to right.
 */
object LabelDirection {
    fun isRtl(text: String): Boolean {
        var depth = 0
        var i = 0
        var rtl: Boolean? = null
        while (i < text.length && rtl == null) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (UCharacter.getType(cp) == Character.UNASSIGNED.toInt()) continue
            val d = UCharacter.getDirection(cp)
            when {
                d in ISOLATES -> depth++
                d == PDI -> if (depth > 0) depth--
                depth > 0 -> Unit
                d == LTR -> rtl = false
                d in RTL -> rtl = true
            }
        }
        return rtl ?: false
    }

    private val ISOLATES = setOf(
        UCharacterDirection.LEFT_TO_RIGHT_ISOLATE.toInt(),
        UCharacterDirection.RIGHT_TO_LEFT_ISOLATE.toInt(),
        UCharacterDirection.FIRST_STRONG_ISOLATE.toInt(),
    )
    private val PDI = UCharacterDirection.POP_DIRECTIONAL_ISOLATE.toInt()
    private val LTR = UCharacterDirection.LEFT_TO_RIGHT.toInt()
    private val RTL = setOf(UCharacterDirection.RIGHT_TO_LEFT.toInt(), UCharacterDirection.RIGHT_TO_LEFT_ARABIC.toInt())
}
