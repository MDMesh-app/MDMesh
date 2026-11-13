package com.mdmesh.agent.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import com.mdmesh.agent.R

/**
 * The dialog's button row (`mdm_dialog.xml`): buttons side by side at the end, or stacked, confirm on top, when
 * their labels don't fit on one row (a long label, a large font, a narrow screen). AppCompat's ButtonBarLayout
 * does the same but is library-internal.
 */
class DialogButtonBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private var stacked = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val stack = rowWidth(heightMeasureSpec) > MeasureSpec.getSize(widthMeasureSpec)
            // Only on a change: rearranging requests another layout.
            if (stack != stacked) setStacked(stack)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The width the visible buttons need side by side, at their natural size. */
    private fun rowWidth(heightMeasureSpec: Int): Int {
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val visible = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
        visible.forEach { measureChild(it, unbounded, heightMeasureSpec) }
        val gaps = (visible.size - 1).coerceAtLeast(0) * resources.getDimensionPixelSize(R.dimen.mdm_dialog_button_gap)
        return paddingStart + paddingEnd + gaps + visible.sumOf { it.measuredWidth }
    }

    private fun setStacked(stack: Boolean) {
        stacked = stack
        orientation = if (stack) VERTICAL else HORIZONTAL
        gravity = if (stack) Gravity.END else Gravity.END or Gravity.CENTER_VERTICAL
        // Reverse the order: the confirm button (last in the row) goes on top of the stack.
        for (i in childCount - 2 downTo 0) bringChildToFront(getChildAt(i))
        // Side by side, 8dp between buttons; stacked, the 48dp touch targets already leave 8dp between the shapes.
        val gap = resources.getDimensionPixelSize(R.dimen.mdm_dialog_button_gap)
        for (i in 0 until childCount) {
            (getChildAt(i).layoutParams as MarginLayoutParams).marginStart = if (stack || i == 0) 0 else gap
        }
    }
}
