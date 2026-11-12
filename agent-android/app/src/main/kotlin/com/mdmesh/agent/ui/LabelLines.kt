package com.mdmesh.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.widget.TextView

/**
 * How an app label breaks into lines (`docs/kiosk-background-scrim.md`, Label lines, 3.3): at word boundaries only
 * (and after a hyphen or slash inside a word, and between CJK characters), never inside a word; a word wider than the
 * box is ellipsized on its own line; greedy fill; the last line takes the rest, ellipsized. Pure Kotlin: [measure]
 * gives a string's width (the label's paint on the device, per-character widths in the vectors).
 */
object LabelLines {
    const val ELLIPSIS = "\u2026"

    /** Only this many code points of a label are laid out (labels come from installed apps, any length). */
    const val MAX_CODE_POINTS = 256

    /**
     * The lines of [text] in a [box] wide, at most [maxLines]. Bounded: at most [MAX_CODE_POINTS] code points are
     * looked at, whatever the label's length, and an ellipsis is found by bisection.
     */
    fun lines(text: String, box: Double, maxLines: Int, measure: (String) -> Double): List<String> {
        val pieces = pieces(normalize(cap(text)))
        val out = mutableListOf<String>()
        var current = ""
        var i = 0
        while (i < pieces.size && maxLines > 0) {
            if (out.size == maxLines - 1) {
                out += ellipsize(rest(current, pieces, i), box, measure)
                return out
            }
            val (piece, spaced) = pieces[i]
            val joined = if (spaced && current.isNotEmpty()) "$current $piece" else current + piece
            when {
                current.isEmpty() && measure(piece) > box -> out += ellipsize(piece, box, measure)
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

    /** The first [MAX_CODE_POINTS] code points of [text], cut between code points (never inside a surrogate pair). */
    fun cap(text: String): String {
        var end = 0
        var count = 0
        while (end < text.length && count < MAX_CODE_POINTS) {
            end += Character.charCount(text.codePointAt(end))
            count++
        }
        return if (end >= text.length) text else text.substring(0, end)
    }

    /**
     * [s] as it is when it fits, else its longest prefix (trailing spaces off) that fits with `…`, then `…`; a prefix
     * ends where a cut is allowed ([cuts]). Found by bisection: a prefix's width grows with its length.
     */
    fun ellipsize(s: String, box: Double, measure: (String) -> Double): String {
        if (measure(s) <= box) return s
        val ellipsis = measure(ELLIPSIS)
        val cuts = cuts(s)
        // Monotone in k (an empty prefix measures 0): bisection finds the longest cut that fits.
        fun fits(k: Int): Boolean = measure(s.substring(0, cuts[k]).trimEnd(' ')) + ellipsis <= box
        var lo = -1 // the longest cut known to fit (-1: none)
        var hi = cuts.size // the shortest cut known not to
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (fits(mid)) lo = mid else hi = mid
        }
        // Nothing fits, or only spaces (a shorter prefix of spaces is empty too): the ellipsis alone.
        return if (lo < 0) ELLIPSIS else s.substring(0, cuts[lo]).trimEnd(' ') + ELLIPSIS
    }

    /**
     * Where [s] may be cut for an ellipsis, shortest first: after each code point that isn't followed by a combining
     * mark, a zero-width joiner or an emoji presentation selector (a mark never loses its letter).
     */
    private fun cuts(s: String): IntArray {
        val out = IntArray(s.length)
        var n = 0
        var i = 0
        while (i < s.length) {
            i += Character.charCount(s.codePointAt(i))
            if (i >= s.length || !joinsPrevious(s.codePointAt(i))) out[n++] = i
        }
        return out.copyOf(n)
    }

    private fun joinsPrevious(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> cp == ZERO_WIDTH_JOINER || cp == EMOJI_PRESENTATION
    }

    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val EMOJI_PRESENTATION = 0xFE0F

    /** A line may break after [c] inside a word: a hyphen, a slash, or a CJK character. */
    private fun breaksAfter(c: Char): Boolean = c == '-' || c == '/' || isCjk(c)

    /** Kana and Han: written without spaces, so a line may break between any two. */
    fun isCjk(c: Char): Boolean = CJK.any { c in it }

    /** Kana, Han extension A, Han, Han compatibility. */
    private val CJK = listOf('\u3040'..'\u30FF', '\u3400'..'\u4DBF', '\u4E00'..'\u9FFF', '\uF900'..'\uFAFF')
}

/**
 * An app label laid out by [LabelLines]: for the width it gets, the label's [label] becomes those lines (each already
 * fits, so the platform never breaks one again), at its own size. The lines are worked out once per label, width,
 * text size and typeface: a measure again (a relayout, a scroll pass) does no layout work.
 */
@SuppressLint("AppCompatCustomView", "ViewConstructor") // a plain framework TextView, made in code only
class LabelView(context: Context, private val maxLabelLines: Int) : TextView(context) {

    /** The app's label, whole; [getText] is the laid-out lines. */
    var label: CharSequence = ""
        set(value) {
            if (field.toString() == value.toString()) return
            field = value
            capped = LabelLines.cap(value.toString())
            requestLayout()
        }

    /** [label] as laid out: its first [LabelLines.MAX_CODE_POINTS] code points. */
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

    private data class Key(val label: String, val box: Int, val textSize: Float, val typeface: Any?)

    private var laidFor: Key? = null

    /** How many times the lines were worked out (for tests: a measure at the same width must not). */
    internal var layouts = 0
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val box = MeasureSpec.getSize(widthMeasureSpec) - totalPaddingLeft - totalPaddingRight
        val key = Key(capped, box, paint.textSize, paint.typeface)
        if (box > 0 && key != laidFor) {
            laidFor = key
            layouts++
            val laid = LabelLines.lines(key.label, box.toDouble(), maxLabelLines) { paint.measureText(it).toDouble() }
            val joined = laid.joinToString("\n")
            if (text.toString() != joined) setText(joined)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        laidFor = null // a new font scale or locale: lay out again
    }
}
