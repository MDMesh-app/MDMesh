package com.mdmesh.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.TextDirectionHeuristics
import android.widget.TextView

/**
 * Where a string may be cut without breaking a character as a reader sees it: extended grapheme cluster boundaries
 * (UAX #29), as offsets after each cluster, shortest first.
 */
fun interface Graphemes {
    fun ends(s: String): IntArray
}

/**
 * How an app label breaks into lines (`docs/kiosk-background-scrim.md`, Label lines, 3.4): at word boundaries only
 * (and after a hyphen or slash inside a word, and between CJK characters), never inside a word; a word wider than the
 * box is ellipsized on its own line; greedy fill; the last line takes the rest, ellipsized; the cap and every
 * ellipsis cut at grapheme cluster boundaries ([Graphemes]). Pure Kotlin: [measure] gives a string's width (the
 * label's paint on the device, per-character widths in the vectors).
 */
object LabelLines {
    const val ELLIPSIS = "\u2026"

    /** Only this many code points of a label are laid out (labels come from installed apps, any length). */
    const val MAX_CODE_POINTS = 256

    /**
     * The lines of [text] in a [box] wide, at most [maxLines]. Bounded: at most [MAX_CODE_POINTS] code points are
     * looked at, whatever the label's length, and an ellipsis is found by bisection.
     */
    fun lines(
        text: String,
        box: Double,
        maxLines: Int,
        graphemes: Graphemes,
        measure: (String) -> Double,
    ): List<String> {
        val pieces = pieces(normalize(cap(text, graphemes)))
        val out = mutableListOf<String>()
        var current = ""
        var i = 0
        while (i < pieces.size && maxLines > 0) {
            if (out.size == maxLines - 1) {
                out += ellipsize(rest(current, pieces, i), box, graphemes, measure)
                return out
            }
            val (piece, spaced) = pieces[i]
            val joined = if (spaced && current.isNotEmpty()) "$current $piece" else current + piece
            when {
                current.isEmpty() && measure(piece) > box -> out += ellipsize(piece, box, graphemes, measure)
                current.isEmpty() || measure(joined) <= box -> current = joined
                else -> {
                    out += current
                    current = ""
                    continue // the piece again, on a new line
                }
            }
            i++
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /** Whitespace runs (tab, line feed, carriage return, space) to one space, trimmed. */
    fun normalize(text: String): String =
        text.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ")

    /** The pieces of normalized [text], each with whether a space goes before it (it starts a word but the first). */
    private fun pieces(text: String): List<Pair<String, Boolean>> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<String, Boolean>>()
        text.split(' ').forEachIndexed { w, word ->
            val piece = StringBuilder()
            var first = true
            fun flush() {
                if (piece.isNotEmpty()) {
                    out += piece.toString() to (w > 0 && first)
                    first = false
                    piece.setLength(0)
                }
            }
            word.forEachIndexed { k, c ->
                if (isCjk(c)) flush()
                piece.append(c)
                if (k + 1 < word.length && breaksAfter(c)) flush()
            }
            flush()
        }
        return out
    }

    private fun rest(current: String, pieces: List<Pair<String, Boolean>>, from: Int): String {
        val s = StringBuilder(current)
        for (k in from until pieces.size) {
            val (piece, spaced) = pieces[k]
            if (spaced && s.isNotEmpty()) s.append(' ')
            s.append(piece)
        }
        return s.toString()
    }

    /**
     * [text] cut to at most [MAX_CODE_POINTS] code points, and back to the last grapheme cluster boundary at or before
     * that, so the cap never splits a cluster (or a surrogate pair). The work is bounded: only the clusters around the
     * cut are looked at, whatever the label's length.
     */
    fun cap(text: String, graphemes: Graphemes): String {
        var end = 0
        var count = 0
        while (end < text.length && count < MAX_CODE_POINTS) {
            end += Character.charCount(text.codePointAt(end))
            count++
        }
        if (end >= text.length) return text
        // The clusters of a window around the cut: a cluster longer than the window is cut at a code point (bounded).
        val window = text.substring(0, minOf(text.length, end + CAP_LOOKAHEAD))
        return text.substring(0, graphemes.ends(window).lastOrNull { it <= end } ?: end)
    }

    /**
     * [s] as it is when it fits, else its longest prefix (trailing spaces off) that fits with `…` (measured together,
     * as it is drawn), then `…`; a prefix ends at a grapheme cluster boundary. Found by bisection: a prefix's width
     * grows with its length.
     */
    fun ellipsize(s: String, box: Double, graphemes: Graphemes, measure: (String) -> Double): String {
        if (measure(s) <= box) return s
        val cuts = graphemes.ends(s)
        fun candidate(k: Int) = s.substring(0, cuts[k]).trimEnd(' ')
        // Monotone in k: bisection finds the longest cut that fits.
        fun fits(k: Int): Boolean = measure(candidate(k) + ELLIPSIS) <= box
        var lo = -1 // the longest cut known to fit (-1: none)
        var hi = cuts.size // the shortest cut known not to
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (fits(mid)) lo = mid else hi = mid
        }
        // Nothing fits, or only spaces (a shorter prefix of spaces is empty too): the ellipsis alone.
        return if (lo < 0) ELLIPSIS else candidate(lo) + ELLIPSIS
    }

    private const val CAP_LOOKAHEAD = 64

    /** A line may break after [c] inside a word: a hyphen, a slash, or a CJK character. */
    private fun breaksAfter(c: Char): Boolean = c == '-' || c == '/' || isCjk(c)

    /** Kana and Han: written without spaces, so a line may break between any two. */
    fun isCjk(c: Char): Boolean = CJK.any { c in it }

    /** Kana, Han extension A, Han, Han compatibility. */
    private val CJK = listOf('\u3040'..'\u30FF', '\u3400'..'\u4DBF', '\u4E00'..'\u9FFF', '\uF900'..'\uFAFF')
}

/**
 * An app label laid out by [LabelLines]: for the width it gets, the label's [label] becomes those lines (each already
 * fits, so the platform never breaks one again), at its own size, in one direction for the whole label (its first
 * strong character), whatever each line holds. The lines are worked out once per label, width and paint (size,
 * typeface, letter spacing, locale): a measure again (a relayout, a scroll pass) does no layout work, and a change to
 * any of those lays it out again. A configuration change (font scale, density) rebuilds the launcher's views
 * ([KioskRenderer.rebuild]), so a new LabelView is measured with the new paint and line count.
 */
@SuppressLint("AppCompatCustomView", "ViewConstructor") // a plain framework TextView, made in code only
class LabelView(context: Context, private val maxLabelLines: Int) : TextView(context) {

    /** The app's label, whole; [getText] is the laid-out lines. */
    var label: CharSequence = ""
        set(value) {
            if (field.toString() == value.toString()) return
            field = value
            capped = LabelLines.cap(value.toString(), IcuGraphemes)
            textDirection = if (TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(capped, 0, capped.length)) {
                TEXT_DIRECTION_RTL
            } else {
                TEXT_DIRECTION_LTR
            }
            requestLayout()
        }

    /** [label] as laid out: its first [LabelLines.MAX_CODE_POINTS] code points, whole clusters. */
    private var capped = ""

    init {
        maxLines = maxLabelLines
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
        } else {
            @Suppress("WrongConstant")
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        }
    }

    private data class Key(
        val label: String,
        val box: Int,
        val textSize: Float,
        val typeface: Any?,
        val letterSpacing: Float,
        val locale: Any?,
    )

    private var laidFor: Key? = null

    /** How many times the lines were worked out (for tests: a measure at the same width must not). */
    internal var layouts = 0
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The grid always measures a cell's label EXACTLY. Unbounded (UNSPECIFIED: a parent that lets it be as wide as
        // it likes), the box is endless: the label on one line.
        val box = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            MeasureSpec.getSize(widthMeasureSpec) - totalPaddingLeft - totalPaddingRight
        }
        val key = Key(capped, box, paint.textSize, paint.typeface, paint.letterSpacing, textLocale)
        if (key != laidFor) {
            laidFor = key
            layouts++
            val laid = if (box <= 0) {
                emptyList()
            } else {
                LabelLines.lines(capped, box.toDouble(), maxLabelLines, IcuGraphemes) {
                    paint.measureText(it).toDouble()
                }
            }
            val joined = laid.joinToString("\n")
            if (text.toString() != joined) setText(joined)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
