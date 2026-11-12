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

    fun lines(text: String, box: Double, maxLines: Int, measure: (String) -> Double): List<String> {
        val pieces = pieces(normalize(text))
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

    /** [s] as it is when it fits, else its longest prefix (trailing spaces off) that fits with `…`, then `…`. */
    fun ellipsize(s: String, box: Double, measure: (String) -> Double): String {
        if (measure(s) <= box) return s
        val ellipsis = measure(ELLIPSIS)
        val prefix = (s.length downTo 1).asSequence()
            .map { s.substring(0, it).trimEnd(' ') }
            .firstOrNull { it.isNotEmpty() && measure(it) + ellipsis <= box }
        return (prefix ?: "") + ELLIPSIS
    }

    /** A line may break after [c] inside a word: a hyphen, a slash, or a CJK character. */
    private fun breaksAfter(c: Char): Boolean = c == '-' || c == '/' || isCjk(c)

    /** Kana and Han: written without spaces, so a line may break between any two. */
    fun isCjk(c: Char): Boolean = CJK.any { c in it }

    /** Kana, Han extension A, Han, Han compatibility. */
    private val CJK = listOf('\u3040'..'\u30FF', '\u3400'..'\u4DBF', '\u4E00'..'\u9FFF', '\uF900'..'\uFAFF')
}

/**
 * An app label laid out by [LabelLines]: on every measure, for the width it gets, the label's [label] becomes those
 * lines (each already fits, so the platform never breaks one again), at its own size.
 */
@SuppressLint("AppCompatCustomView", "ViewConstructor") // a plain framework TextView, made in code only
class LabelView(context: Context, private val maxLabelLines: Int) : TextView(context) {

    /** The app's label, whole; [getText] is the laid-out lines. */
    var label: CharSequence = ""
        set(value) {
            if (field.toString() == value.toString()) return
            field = value
            requestLayout()
        }

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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val box = MeasureSpec.getSize(widthMeasureSpec) - totalPaddingLeft - totalPaddingRight
        if (box > 0) {
            val laid = LabelLines.lines(label.toString(), box.toDouble(), maxLabelLines) {
                paint.measureText(it).toDouble()
            }
            val joined = laid.joinToString("\n")
            if (text.toString() != joined) setText(joined)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
