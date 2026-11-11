package com.mdmesh.agent.ui

import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
 * The confirm button calls `onConfirm` and doesn't close the dialog by itself: callers that close on confirm call
 * [dismiss] (the exit PIN dialog stays open on a wrong PIN). Cancel cancels the dialog.
 */
class MdmDialog private constructor(
    val alert: AlertDialog,
    /** The confirm button (the danger-styled one when the dialog is [Spec.danger]). */
    val confirmButton: Button,
    /** The inflated [Spec.body], if any. */
    val body: View?,
) {

    /** What the dialog shows, text already resolved. [body] is a layout shown under the message. */
    data class Spec(
        val title: CharSequence,
        val confirm: CharSequence,
        val message: CharSequence? = null,
        val cancel: CharSequence? = null,
        @DrawableRes val icon: Int = 0,
        val danger: Boolean = false,
        @LayoutRes val body: Int = 0,
    )

    fun show(): MdmDialog = apply { alert.show() }

    fun dismiss() = alert.dismiss()

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
            // Accessibility services announce the window title; AppCompat's own title row stays hidden.
            alert.window?.setTitle(spec.title)
            val (confirm, unused) = root.findViewById<Button>(R.id.mdm_dialog_confirm) to
                root.findViewById<Button>(R.id.mdm_dialog_confirm_danger)
            val shown = if (spec.danger) unused else confirm
            (if (spec.danger) confirm else unused).visibility = View.GONE
            shown.visibility = View.VISIBLE
            shown.text = spec.confirm
            val dialog = MdmDialog(alert, shown, body)
            shown.setOnClickListener { onConfirm(dialog) }
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
            // First baseline from the view's top; TextView adds the font padding (fontMetrics.top) by default.
            val baseline = title.paddingTop - if (title.includeFontPadding) metrics.top else metrics.ascent
            val caps = Rect().also { title.paint.getTextBounds(CAP_SAMPLE, 0, CAP_SAMPLE.length, it) }.height()
            (icon.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                iconTopFor(baseline, caps, drawable.intrinsicHeight)
        }

        /** The icon's top offset that puts its centre on the cap-height centre of a line with this [baseline]. */
        internal fun iconTopFor(baseline: Int, capHeight: Int, iconHeight: Int): Int =
            (baseline - capHeight / 2f - iconHeight / 2f).roundToInt()

        private const val CAP_SAMPLE = "H"
    }
}
