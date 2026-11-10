package com.mdmesh.agent.ui

import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import com.mdmesh.agent.R

/**
 * Restyles a shown dialog's confirm button as a destructive action (v2 danger: alert line and alert ink) instead
 * of the theme's filled ink button. Call after [AlertDialog.show]: the buttons exist only then.
 */
fun AlertDialog.markConfirmDanger() {
    getButton(AlertDialog.BUTTON_POSITIVE)?.restyle(R.drawable.mdm_btn_danger, R.color.mdm_btn_danger_label)
}

private fun Button.restyle(background: Int, textColors: Int) {
    // A new background resets the padding to the drawable's own; keep the style's.
    val start = paddingStart
    val top = paddingTop
    val end = paddingEnd
    val bottom = paddingBottom
    this.background = AppCompatResources.getDrawable(context, background)
    setPaddingRelative(start, top, end, bottom)
    setTextColor(AppCompatResources.getColorStateList(context, textColors))
}
