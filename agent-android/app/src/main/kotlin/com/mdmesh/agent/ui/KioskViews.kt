package com.mdmesh.agent.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Layout
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.mdmesh.agent.R
import kotlin.math.roundToInt

/**
 * Builds the kiosk launcher's views in one [palette] (the admin's kiosk colours). Nothing here reads the app
 * theme's colours, so a system light/dark switch leaves the launcher as it is. No behaviour lives here: click
 * handlers stay in KioskLauncherActivity.
 */
/**
 * Builds the kiosk launcher's views in [palette]. [onImage]: the screen lies over the background image, so every
 * text that isn't on a fill of its own gets a [TextPlate] (drawn only where the scrim alone leaves it under 4.5:1).
 * A plated text's padding sits in negative margins ([plated]), so the text itself stays where it is without one.
 */
class KioskViews(private val context: Context, val palette: KioskPalette, private val onImage: Boolean = false) {

    private val density = context.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density).roundToInt()

    /** Screen side margin: 16dp on phones, 24dp from 600dp wide. */
    val gutter: Int get() = context.resources.getDimensionPixelSize(R.dimen.mdm_gutter)

    /** Whether a text over the image gets a [TextPlate]. */
    enum class Plate {
        /** It sits on a fill of its own (a callout). */
        NONE,

        /** Its own plate. */
        OWN,

        /** An app label: a plate as wide as its grid row's widest. */
        ROW,
    }

    /** A text; over the image ([onImage]) with a [plate]. */
    fun text(s: CharSequence, sizeSp: Float, color: Int, medium: Boolean = false, plate: Plate = Plate.OWN): TextView =
        TextView(context).apply {
            text = s
            // No hyphens: line breaks then match the console's preview (Android 6 to 12 hyphenate by default).
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (medium) typeface = Typeface.create(MEDIUM, Typeface.NORMAL)
            if (onImage && plate != Plate.NONE) {
                val padH = dp(PLATE_PAD_H_DP)
                background = TextPlate(this, palette.background, TextPlates.radius(density), padH, plate == Plate.ROW)
                // A label's text box is its cell less 24dp (the row plate's at most the cell less 8dp, less padding).
                val inset = if (plate == Plate.ROW) dp(LabelRows.GAP_DP / 2 + LabelRows.PAD_DP) else padH
                setPadding(inset, dp(PLATE_PAD_V_DP), inset, dp(PLATE_PAD_V_DP))
            }
        }

    /** A centred column filling its parent, for the splash, hold and idle screens. */
    fun centredColumn(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipToPadding = false // plates reach into the padding
        gravity = Gravity.CENTER
        setPadding(gutter, 0, gutter, 0)
        layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
    }

    fun icon(@DrawableRes res: Int, sizeDp: Int, tint: Int): ImageView = ImageView(context).apply {
        setImageDrawable(AppCompatResources.getDrawable(context, res))
        imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Indeterminate spinner in the text colour (the splash). Not a TalkBack stop: the line under it says it. */
    fun spinner(): ProgressBar = ProgressBar(context).apply {
        isIndeterminate = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        indeterminateTintList = ColorStateList.valueOf(palette.text)
        layoutParams = LinearLayout.LayoutParams(dp(SPINNER_DP), dp(SPINNER_DP))
    }

    /**
     * One app cell: icon over a label of up to two lines (three at larger font sizes), with a rounded pressed
     * highlight and a 2dp focus ring for keyboards and D-pads. TalkBack reads it as a button named by its label.
     */
    fun appCell(iconPx: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipToPadding = false // a label's plate reaches 4dp into the cell's padding
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(CELL_PAD_H_DP), dp(CELL_PAD_V_DP), dp(CELL_PAD_H_DP), dp(CELL_PAD_V_DP))
        isClickable = true
        isFocusable = true
        background = LayerDrawable(
            arrayOf(
                InsetDrawable(
                    RippleDrawable(ColorStateList.valueOf(palette.ripple), null, rounded(dp(CELL_RADIUS_DP), WHITE)),
                    dp(CELL_INSET_DP),
                ),
                // The ring sits 2dp outside the highlight (in the cell's 4dp inset).
                focusRing(rounded(dp(CELL_RADIUS_DP + CELL_INSET_DP), TRANSPARENT), palette.focus),
            ),
        )
        ViewCompat.setAccessibilityDelegate(this, ButtonRole)
        addView(
            ImageView(context).apply {
                id = R.id.kiosk_app_icon
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
            },
        )
        addView(
            text("", LABEL_SP, palette.text, plate = Plate.ROW).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = labelLines(context.resources.configuration.fontScale)
                ellipsize = TextUtils.TruncateAt.END
                // The cell is announced once, by its content description.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = plated(
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(LABEL_GAP_DP) },
                )
            },
        )
    }

    /** Puts [app] in a cell made by [appCell]. */
    fun bindAppCell(cell: View, app: KioskApp) {
        val parts = cell as LinearLayout
        // A copy per cell: the cached Drawable itself is never attached to a view (a Drawable has one owner).
        val icon = app.icon.constantState?.newDrawable(context.resources)?.mutate() ?: app.icon
        (parts.getChildAt(0) as ImageView).setImageDrawable(icon)
        (parts.getChildAt(1) as TextView).text = app.label
        cell.contentDescription = app.label
    }

    /**
     * The empty launcher: an info icon and the message in a 1dp frame (v2 callout shape), in the secondary colour
     * derived from the admin's text colour, so it reads on any kiosk background.
     */
    fun emptyNotice(message: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(CALLOUT_PAD_H_DP), dp(CALLOUT_PAD_V_DP), dp(CALLOUT_PAD_H_DP), dp(CALLOUT_PAD_V_DP))
        background = rounded(dp(CALLOUT_RADIUS_DP), palette.background).apply { setStroke(dp(1), palette.line) }
        val info = icon(R.drawable.ic_v2_info, CALLOUT_ICON_DP, palette.secondary).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(CALLOUT_ICON_GAP_DP)
        }
        val body = text(message, BODY_SP, palette.secondary, plate = Plate.NONE).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        addView(info)
        addView(body)
        info.centerOnFirstLineOf(body)
    }

    /** A button in the palette: [shape] behind a [ripple], 40dp visible in a 48dp target. */
    fun button(label: String, textColor: Int, ripple: Int, shape: GradientDrawable): Button =
        Button(context, null, 0, R.style.Widget_MdmAgent_Button).apply {
            text = label
            setTextColor(textColor)
            keepingPadding {
                // 40dp visible inside the style's 48dp minimum height, like the theme's buttons. The focus ring is
                // drawn 2dp inside the shape in the label's colour, so it shows on the filled button's fill and on the
                // outlined one's background alike.
                val ring = focusRing(
                    rounded(dp(BUTTON_RADIUS_DP - FOCUS_OFFSET_DP), TRANSPARENT),
                    textColor,
                    inset = dp(FOCUS_OFFSET_DP),
                )
                background = InsetDrawable(
                    LayerDrawable(arrayOf(RippleDrawable(ColorStateList.valueOf(ripple), shape, null), ring)),
                    0, dp(BUTTON_INSET_DP), 0, dp(BUTTON_INSET_DP),
                )
            }
            minWidth = dp(BUTTON_MIN_WIDTH_DP)
        }

    /** [shape] stroked 2dp in [color], drawn only while focused; [inset] px in from the bounds. */
    private fun focusRing(shape: GradientDrawable, color: Int, inset: Int = 0) = StateListDrawable().apply {
        shape.setStroke(dp(FOCUS_RING_DP), color)
        addState(intArrayOf(android.R.attr.state_focused), InsetDrawable(shape, inset))
    }

    /** TalkBack calls an app cell a button ("Inventory, button"); it is a plain clickable layout otherwise. */
    private object ButtonRole : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }

    companion object {
        fun rounded(radiusPx: Int, fill: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fill)
        }

        /** App label lines: two, three once the font is larger than the default (no name cut at "Safety Che…"). */
        fun labelLines(fontScale: Float): Int = if (fontScale > 1f) LABEL_LINES_LARGE else LABEL_LINES

        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MEDIUM = "sans-serif-medium"
        const val TITLE_SP = 20f
        const val BODY_SP = 14f
        const val LABEL_SP = 14f
        const val PLATE_PAD_H_DP = 8
        const val PLATE_PAD_V_DP = 4
        private const val LABEL_LINES = 2
        private const val LABEL_LINES_LARGE = 3
        private const val FOCUS_RING_DP = 2
        private const val FOCUS_OFFSET_DP = 2
        private const val TRANSPARENT = 0
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val SPINNER_DP = 40
        private const val CELL_PAD_H_DP = 8
        private const val CELL_PAD_V_DP = 12
        private const val CELL_INSET_DP = 4
        private const val CELL_RADIUS_DP = 14
        private const val LABEL_GAP_DP = 8
        private const val CALLOUT_PAD_H_DP = 12
        private const val CALLOUT_PAD_V_DP = 10
        private const val CALLOUT_RADIUS_DP = 10
        private const val CALLOUT_ICON_DP = 16
        private const val CALLOUT_ICON_GAP_DP = 10
        const val BUTTON_RADIUS_DP = 10
        private const val BUTTON_INSET_DP = 4
        private const val BUTTON_MIN_WIDTH_DP = 120
    }
}

/** Primary button (hold screen "Open again"): filled with the text colour, labelled in the background colour. */
fun KioskViews.filledButton(label: String): Button = button(
    label,
    palette.buttonLabel,
    palette.buttonRipple,
    KioskViews.rounded(dp(KioskViews.BUTTON_RADIUS_DP), palette.buttonFill),
)

/**
 * Secondary button ("Exit kiosk"): the background colour with a 1dp edge and label in the text colour. Opaque,
 * because it floats over the app grid.
 */
fun KioskViews.outlinedButton(label: String): Button = button(
    label,
    palette.buttonFill,
    palette.ripple,
    KioskViews.rounded(dp(KioskViews.BUTTON_RADIUS_DP), palette.background).apply {
        setStroke(dp(1), palette.buttonFill)
    },
)

/** A full-width centred line of text, [topDp] below the view before it. */
fun KioskViews.centredLine(s: String, sizeSp: Float, color: Int, topDp: Int, medium: Boolean = false): TextView =
    text(s, sizeSp, color, medium).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = plated(
            LinearLayout.LayoutParams(KioskViews.MATCH, KioskViews.WRAP).apply { topMargin = dp(topDp) },
        )
    }

/** Gives [view] the screen's side margins in a vertical LinearLayout, [topPx] below the view before it. */
fun <T : View> KioskViews.inGutter(view: T, topPx: Int): T = view.apply {
    // Symmetric, so plain left and right margins (a plated text's are narrowed by its plate's padding).
    layoutParams = plated(
        LinearLayout.LayoutParams(KioskViews.MATCH, KioskViews.WRAP).apply {
            leftMargin = gutter
            rightMargin = gutter
            topMargin = topPx
        },
    )
}

/**
 * [lp] for this view: a plated text's plate padding taken back out of its margins, so the text sits where it would
 * without a plate.
 */
fun <T : ViewGroup.MarginLayoutParams> View.plated(lp: T): T = lp.apply {
    if (background is TextPlate) {
        val h = (KioskViews.PLATE_PAD_H_DP * resources.displayMetrics.density).roundToInt()
        val v = (KioskViews.PLATE_PAD_V_DP * resources.displayMetrics.density).roundToInt()
        leftMargin -= h
        rightMargin -= h
        topMargin -= v
        bottomMargin -= v
    }
}
