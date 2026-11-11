package com.mdmesh.agent.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import com.mdmesh.agent.R
import kotlin.math.roundToInt

/**
 * The agent's dialog: v2 Modal (title with an optional icon, optional message and body, Cancel and a confirm button)
 * in our own layout, `mdm_dialog.xml`, inside an androidx AlertDialog window, which keeps the theme's surface,
 * corners and scrim. AppCompat's title/message/button template isn't used: its rows don't share one left edge and
 * its spacing isn't v2's (see the layout).
 *
 * Shown only through a [DialogSlot], which keeps it across recreations and config changes.
 *
 * The confirm button calls `onConfirm` and doesn't close the dialog by itself: callers that close on confirm call
 * [dismiss] (the exit PIN dialog stays open on a wrong PIN). Cancel cancels the dialog.
 */
class MdmDialog private constructor(
    private val alert: AlertDialog,
    /** The inflated [Spec.body], if any. */
    val body: View?,
) : SlotDialog {

    override val builtFor: Configuration = Configuration(alert.context.resources.configuration)

    /** The body's text field, if it has one (the exit PIN). */
    override fun typedText(): CharSequence? = body?.let(::firstEditText)?.text

    /** What the dialog shows, text already resolved. [body] is a layout shown under the message. */
    data class Spec(
        val title: CharSequence,
        val confirm: CharSequence,
        val message: CharSequence? = null,
        val cancel: CharSequence? = null,
        @DrawableRes val icon: Int = 0,
        val danger: Boolean = false,
        @LayoutRes val body: Int = 0,
        /** Open the keyboard with the dialog (the exit PIN). */
        val keyboard: Boolean = false,
    )

    /** Shown only through a [DialogSlot]. Sized here, from the activity's current configuration. */
    override fun show(key: DialogSlot.Key) {
        val res = alert.context.resources
        val metrics = res.displayMetrics
        val portrait = metrics.widthPixels < metrics.heightPixels
        // AppCompat's own share of the screen (95% portrait, 65% landscape on phones), capped at M3's 560dp.
        val fraction = TypedValue().let {
            val attr = if (portrait) android.R.attr.windowMinWidthMinor else android.R.attr.windowMinWidthMajor
            if (alert.context.theme.resolveAttribute(attr, it, true) && it.type == TypedValue.TYPE_FRACTION) {
                it.getFraction(1f, 1f)
            } else {
                1f
            }
        }
        val max = res.getDimensionPixelSize(R.dimen.mdm_dialog_max_width) +
            2 * res.getDimensionPixelSize(R.dimen.mdm_dialog_inset)
        alert.show()
        // After show(): installing the decor (on the first show) resets a floating window to WRAP_CONTENT. Same
        // message as the show, before the first layout pass, so it opens at this width.
        alert.window?.setLayout(windowWidth(metrics.widthPixels, fraction, max), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun dismiss() = alert.dismiss()

    override fun setOnDismiss(listener: () -> Unit) = alert.setOnDismissListener { listener() }

    /** [block] runs each time the dialog is shown (focus a field, say). */
    fun setOnShown(block: () -> Unit) = alert.setOnShowListener { block() }

    companion object {

        /** Builds (doesn't show) the dialog on [context]: an activity, never the application context (the theme). */
        fun create(context: Context, spec: Spec, onConfirm: (MdmDialog) -> Unit): MdmDialog {
            val builder = AlertDialog.Builder(context)
            // Inflated from the dialog's themed context, so the views take ThemeOverlay.MdmAgent.Dialog.
            val inflater = LayoutInflater.from(builder.context)
            val root = inflater.inflate(R.layout.mdm_dialog, null, false)
            val title = root.findViewById<TextView>(R.id.mdm_dialog_title)
            title.text = spec.title
            ViewCompat.setAccessibilityHeading(title, true)
            root.findViewById<TextView>(R.id.mdm_dialog_message).apply {
                text = spec.message
                visibility = if (spec.message.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            if (spec.icon != 0) {
                root.findViewById<ImageView>(R.id.mdm_dialog_icon).apply {
                    setImageDrawable(AppCompatResources.getDrawable(builder.context, spec.icon))
                    visibility = View.VISIBLE
                    alignIcon(this, title)
                }
            }
            val body = if (spec.body == 0) {
                null
            } else {
                val holder = root.findViewById<FrameLayout>(R.id.mdm_dialog_body)
                holder.visibility = View.VISIBLE
                inflater.inflate(spec.body, holder, true)
                holder.getChildAt(0)
            }
            val alert = builder.setView(root).create()
            alert.window?.apply {
                // Accessibility services announce the window title; AppCompat's own title row stays hidden.
                setTitle(spec.title)
                setSoftInputMode(softInputMode(spec.keyboard))
            }
            val filled = root.findViewById<Button>(R.id.mdm_dialog_confirm)
            val danger = root.findViewById<Button>(R.id.mdm_dialog_confirm_danger)
            val confirm = if (spec.danger) danger else filled
            (if (spec.danger) filled else danger).visibility = View.GONE
            confirm.visibility = View.VISIBLE
            confirm.text = spec.confirm
            val dialog = MdmDialog(alert, body)
            confirm.setOnClickListener { onConfirm(dialog) }
            spec.cancel?.let { label ->
                root.findViewById<Button>(R.id.mdm_dialog_cancel).apply {
                    text = label
                    visibility = View.VISIBLE
                    setOnClickListener { alert.cancel() }
                }
            }
            return dialog
        }

        /**
         * Centres [icon] on the cap height of [title]'s first line, the title's optical centre. A plain
         * centre_vertical misses it (it centres on the whole line box, descender space included) and drifts when
         * the title wraps. Measured from the title's own paint, so it holds for any font and font scale.
         */
        private fun alignIcon(icon: ImageView, title: TextView) {
            val drawable = icon.drawable ?: return
            val metrics = title.paint.fontMetricsInt
            val baseline = firstBaseline(title.paddingTop, title.includeFontPadding, metrics.top, metrics.ascent)
            val caps = Rect().also { title.paint.getTextBounds(CAP_SAMPLE, 0, CAP_SAMPLE.length, it) }.height()
            (icon.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                iconTopFor(baseline, caps, drawable.intrinsicHeight)
        }

        private fun firstEditText(view: View): EditText? = when (view) {
            is EditText -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { firstEditText(view.getChildAt(it)) }
            else -> null
        }

        /**
         * The first line's baseline from a TextView's top: its padding, then the font's ascent, or its `top` when the
         * TextView adds the font padding (the default).
         */
        internal fun firstBaseline(paddingTop: Int, includeFontPadding: Boolean, top: Int, ascent: Int): Int =
            paddingTop - if (includeFontPadding) top else ascent

        /** The dialog window's width: the theme's [fraction] of the screen, at most [maxPx]. */
        internal fun windowWidth(screenWidthPx: Int, fraction: Float, maxPx: Int): Int =
            minOf((screenWidthPx * fraction).roundToInt(), maxPx)

        /**
         * The window gives way to the keyboard (ADJUST_RESIZE): the body scrolls and the buttons stay above it. With
         * [keyboard] the keyboard opens with the dialog.
         */
        internal fun softInputMode(keyboard: Boolean): Int =
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                if (keyboard) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE else 0

        /** The icon's top offset that puts its centre on the cap-height centre of a line with this [baseline]. */
        internal fun iconTopFor(baseline: Int, capHeight: Int, iconHeight: Int): Int =
            (baseline - capHeight / 2f - iconHeight / 2f).roundToInt()

        private const val CAP_SAMPLE = "H"
    }
}
