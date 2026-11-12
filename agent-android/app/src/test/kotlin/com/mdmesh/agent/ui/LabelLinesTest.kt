package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The label-line rule is bounded whatever the label (they come from installed apps), and bisection is exact. */
class LabelLinesTest {

    /** A width per code point, positive, so a prefix's width grows with its length; marks are 0. */
    private fun widthOf(cp: Int): Double = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> 0.0
        else -> if (cp == ' '.code) 4.0 else 6.0 + cp % 7
    }

    private fun measure(calls: IntArray? = null): (String) -> Double = { s ->
        calls?.let { it[0]++ }
        var w = 0.0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            w += widthOf(cp)
            i += Character.charCount(cp)
        }
        w
    }

    private fun bounded(label: String) {
        val calls = IntArray(1)
        val start = System.nanoTime()
        val lines = LabelLines.lines(label, 120.0, 3, measure(calls))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("${label.take(12)}…: $ms ms", ms < 250)
        assertTrue("${calls[0]} measures", calls[0] < 2_000)
        assertTrue(lines.size <= 3)
    }

    @Test fun `a pathological label is laid out in bounded time and work`() {
        bounded("W".repeat(100_000))
        bounded("在".repeat(100_000))
        bounded("ab ".repeat(40_000))
        bounded("a-".repeat(50_000))
        bounded("😀".repeat(60_000)) // emoji: surrogate pairs
        bounded("é".repeat(60_000)) // combining marks
    }

    @Test fun `the cap counts code points and never splits a surrogate pair`() {
        val emoji = "😀"
        val capped = LabelLines.cap(emoji.repeat(300))
        assertEquals(LabelLines.MAX_CODE_POINTS * 2, capped.length)
        assertEquals("a".repeat(10), LabelLines.cap("a".repeat(10)))
    }

    /** The plain rule: try every allowed cut, longest first. */
    private fun linear(s: String, box: Double, measure: (String) -> Double): String {
        if (measure(s) <= box) return s
        val ell = measure(LabelLines.ELLIPSIS)
        var found: String? = null
        var n = s.length
        while (n > 0 && found == null) {
            val atBoundary = !Character.isLowSurrogate(s.getOrElse(n) { 'x' }) || !Character.isHighSurrogate(s[n - 1])
            val next = if (n < s.length) s.codePointAt(n) else -1
            val joins = next >= 0 && (
                Character.getType(next).toByte() in listOf(
                    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
                ) || next == 0x200D || next == 0xFE0F
                )
            if (atBoundary && !joins) {
                val p = s.substring(0, n).trimEnd(' ')
                if (p.isNotEmpty() && measure(p) + ell <= box) found = p + LabelLines.ELLIPSIS
            }
            n--
        }
        return found ?: LabelLines.ELLIPSIS
    }

    @Test fun `bisection gives the same ellipsis as trying every cut`() {
        val random = Random(17)
        val alphabet = listOf("a", "W", " ", "-", "́", "在", "😀", "‍", "️", "א")
        repeat(3_000) {
            val s = (0 until random.nextInt(1, 40)).joinToString("") { alphabet[random.nextInt(alphabet.size)] }
            val box = random.nextDouble(0.0, 200.0)
            assertEquals("'$s' in $box", linear(s, box, measure()), LabelLines.ellipsize(s, box, measure()))
        }
    }
}
