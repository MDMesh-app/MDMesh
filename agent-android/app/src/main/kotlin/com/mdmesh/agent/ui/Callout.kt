package com.mdmesh.agent.ui

import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.mdmesh.agent.R
import kotlin.math.roundToInt

/** The v2 callout tones the status screen uses: soft fill, 1dp line, ink text and icon. */
enum class CalloutTone(@DrawableRes val background: Int, @ColorRes val ink: Int, @DrawableRes val icon: Int) {
    OK(R.drawable.mdm_callout_ok, R.color.mdm_ok_ink, R.drawable.ic_v2_check),
    WARN(R.drawable.mdm_callout_warn, R.color.mdm_warn_ink, R.drawable.ic_v2_warning),
    ALERT(R.drawable.mdm_callout_alert, R.color.mdm_alert_ink, R.drawable.ic_v2_alert),
}

/**
 * A `view_callout` layout: tone, title and an optional body line. TalkBack reads it as one stop, title then body;
 * with [announce] it is a polite live region, so it is read out when it appears or changes (an error the person
 * didn't ask for, like a failed return or a stuck enrolment).
 */
class Callout(private val root: View, announce: Boolean = false) {

    private val icon: ImageView = root.findViewById(R.id.callout_icon)
    private val title: TextView = root.findViewById(R.id.callout_title)
    private val body: TextView = root.findViewById(R.id.callout_body)

    init {
        icon.centerOnFirstLineOf(title)
        title.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        body.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (announce) root.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun show(tone: CalloutTone, title: CharSequence, body: CharSequence? = null) {
        val context = root.context
        root.keepingPadding { root.background = AppCompatResources.getDrawable(context, tone.background) }
        val ink = ContextCompat.getColor(context, tone.ink)
        icon.setImageDrawable(AppCompatResources.getDrawable(context, tone.icon))
        icon.imageTintList = ColorStateList.valueOf(ink)
        this.title.text = title
        this.title.setTextColor(ink)
        this.body.text = body
        this.body.setTextColor(ink)
        this.body.visibility = if (body.isNullOrEmpty()) View.GONE else View.VISIBLE
        root.contentDescription = if (body.isNullOrEmpty()) title else "$title. $body"
        root.visibility = View.VISIBLE
    }

    fun hide() {
        root.visibility = View.GONE
    }
}

/** Sets a new background without losing the view's own padding (a drawable's padding would replace it). */
internal inline fun View.keepingPadding(block: () -> Unit) {
    val start = paddingStart
    val top = paddingTop
    val end = paddingEnd
    val bottom = paddingBottom
    block()
    setPaddingRelative(start, top, end, bottom)
}

/**
 * Keeps this view (an icon, the wordmark square) vertically centred on the cap height of [text]'s first line, its
 * optical centre (as MdmDialog aligns its title icon), at any font size and however many lines [text] wraps to.
 * Both must share a parent.
 */
fun View.centerOnFirstLineOf(text: TextView) {
    val icon = this
    val parent = icon.parent as? ViewGroup ?: return
    val caps = Rect()
    parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val capCentre = firstLineCapCentre(text, parent, caps) ?: return@addOnLayoutChangeListener
        icon.translationY = capCentre - (icon.top + icon.height / 2f)
    }
}

/**
 * As [centerOnFirstLineOf], for a touch target: placed by its top margin instead of a translation, so its parent
 * grows to hold all of it. A translated target that sticks out of its parent loses the touches that land outside
 * the parent, as the parent never receives them. The parent must lay this view out from its top (the default in a
 * horizontal LinearLayout).
 */
fun View.placeOnFirstLineOf(text: TextView) {
    val target = this
    val parent = target.parent as? ViewGroup ?: return
    val caps = Rect()
    parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val capCentre = firstLineCapCentre(text, parent, caps) ?: return@addOnLayoutChangeListener
        val params = target.layoutParams as ViewGroup.MarginLayoutParams
        val top = (capCentre - target.height / 2f).roundToInt().coerceAtLeast(0)
        if (params.topMargin != top) {
            params.topMargin = top
            // Not from inside this layout pass: on the next frame (it settles there, the text doesn't move).
            target.post { target.layoutParams = params }
        }
    }
}

/** The cap-height centre of [text]'s first line, from the top of its ancestor [parent]; null before layout. */
private fun firstLineCapCentre(text: TextView, parent: View, caps: Rect): Float? {
    var textTop = 0f
    var v: View? = text
    while (v != null && v !== parent) {
        textTop += v.top
        v = v.parent as? View
    }
    val layout = text.layout
    if (v == null || layout == null) return null
    text.paint.getTextBounds(CAP_SAMPLE, 0, CAP_SAMPLE.length, caps)
    return textTop + text.totalPaddingTop + layout.getLineBaseline(0) - caps.height() / 2f
}

private const val CAP_SAMPLE = "H"
