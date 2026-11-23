package com.mdmesh.agent.ui

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import kotlin.math.roundToInt

/**
 * The v2 wordmark (web/src/ui/Sidebar.css `.v2-wordmark`): an ink square, then "MDM" bold and "esh" medium in the
 * secondary colour. The square and the gap scale with the text size, so the mark keeps its proportions at any font
 * size, and the square is centred on the text line.
 */
object Wordmark {

    /** Fills [mark] (a plain View) and [text], already side by side in one horizontal LinearLayout. */
    fun bind(mark: View, text: TextView, brand: String, @ColorInt ink: Int, @ColorInt secondary: Int) {
        text.text = spans(brand, ink, secondary)
        text.includeFontPadding = false
        text.letterSpacing = LETTER_SPACING
        val size = (text.textSize * MARK_SCALE).roundToInt()
        mark.setBackgroundColor(ink)
        mark.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginEnd = (text.textSize * GAP_SCALE).roundToInt()
        }
        mark.centerOnFirstLineOf(text)
    }

    private fun spans(brand: String, ink: Int, secondary: Int): SpannableString {
        val split = if (brand.startsWith(PREFIX)) PREFIX.length else brand.length
        return SpannableString(brand).apply {
            setSpan(ForegroundColorSpan(ink), 0, brand.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (split < brand.length) {
                setSpan(TypefaceSpan(MEDIUM), split, brand.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(secondary), split, brand.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private const val PREFIX = "MDM"
    private const val MEDIUM = "sans-serif-medium"
    private const val LETTER_SPACING = -0.01f

    /** Console: a 10px square and a 10px gap beside 18px text. */
    private const val MARK_SCALE = 0.55f
    private const val GAP_SCALE = 0.55f
}
