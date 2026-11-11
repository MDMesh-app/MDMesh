package com.mdmesh.agent.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
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
import com.mdmesh.agent.R
import kotlin.math.roundToInt

/**
 * Builds the kiosk launcher's views in one [palette] (the admin's kiosk colours). Nothing here reads the app
 * theme's colours, so a system light/dark switch leaves the launcher as it is. No behaviour lives here: click
 * handlers stay in KioskLauncherActivity.
 */
class KioskViews(private val context: Context, val palette: KioskPalette) {

    private val density = context.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density).roundToInt()

    /** Screen side margin: 16dp on phones, 24dp from 600dp wide. */
    val gutter: Int get() = context.resources.getDimensionPixelSize(R.dimen.mdm_gutter)

    fun text(s: CharSequence, sizeSp: Float, color: Int, medium: Boolean = false): TextView =
        TextView(context).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (medium) typeface = Typeface.create(MEDIUM, Typeface.NORMAL)
        }

    /** A centred column filling its parent, for the splash, hold and idle screens. */
    fun centredColumn(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
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

    /** Indeterminate spinner in the text colour (the splash). */
    fun spinner(): ProgressBar = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(palette.text)
        layoutParams = LinearLayout.LayoutParams(dp(SPINNER_DP), dp(SPINNER_DP))
    }

    /** One app cell: icon over a label of up to two lines, with a rounded pressed and focus highlight. */
    fun appCell(iconPx: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(CELL_PAD_H_DP), dp(CELL_PAD_V_DP), dp(CELL_PAD_H_DP), dp(CELL_PAD_V_DP))
        isClickable = true
        isFocusable = true
        background = InsetDrawable(
            RippleDrawable(ColorStateList.valueOf(palette.ripple), null, rounded(dp(CELL_RADIUS_DP), WHITE)),
            dp(CELL_INSET_DP),
        )
        addView(
            ImageView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
            },
        )
        addView(
            text("", LABEL_SP, palette.text).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                // The cell is announced once, by its content description.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(LABEL_GAP_DP) }
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
        val body = text(message, BODY_SP, palette.secondary).apply {
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
                // 40dp visible inside the style's 48dp minimum height, like the theme's buttons.
                background = InsetDrawable(
                    RippleDrawable(ColorStateList.valueOf(ripple), shape, null),
                    0, dp(BUTTON_INSET_DP), 0, dp(BUTTON_INSET_DP),
                )
            }
            minWidth = dp(BUTTON_MIN_WIDTH_DP)
        }

    companion object {
        fun rounded(radiusPx: Int, fill: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fill)
        }

        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MEDIUM = "sans-serif-medium"
        const val TITLE_SP = 20f
        const val BODY_SP = 14f
        const val LABEL_SP = 13f
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
        layoutParams = LinearLayout.LayoutParams(KioskViews.MATCH, KioskViews.WRAP).apply { topMargin = dp(topDp) }
    }

/** Gives [view] the screen's side margins in a vertical LinearLayout, [topPx] below the view before it. */
fun <T : View> KioskViews.inGutter(view: T, topPx: Int): T = view.apply {
    layoutParams = LinearLayout.LayoutParams(KioskViews.MATCH, KioskViews.WRAP).apply {
        marginStart = gutter
        marginEnd = gutter
        topMargin = topPx
    }
}
