package com.mdmesh.agent.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.mdmesh.agent.R

/**
 * The kiosk app grid: equal-width columns, as many as fit [slotPx] (at least [GridColumns.MIN]), worked out from the
 * width it is measured at. So the columns follow rotation, split screen and the system bars without rebuilding the
 * grid. Each row is as tall as its tallest cell, and every cell in the row is stretched to it, so pressed
 * highlights line up when one label wraps to two lines. [submit] updates the cells in place, keyed, the way
 * DiffUtil would: unchanged apps keep their views.
 */
class AppGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** The narrowest a column may be. */
    var slotPx: Int = 0
        set(value) {
            field = value
            requestLayout()
        }

    private var columns = GridColumns.MIN
    private var cellWidth = 0

    /** Row heights from the last measure; one slot per child, sized as children arrive, not while measuring. */
    private var rowHeights = IntArray(0)

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (rowHeights.size < childCount) rowHeights = IntArray(childCount)
    }

    /**
     * Shows one cell per item, in order. A cell whose [key] was already shown is kept (and [bind] again only when
     * its item changed); new keys get a [create]d cell; the rest are removed.
     */
    fun <T : Any> submit(items: List<T>, key: (T) -> String, create: (T) -> View, bind: (View, T) -> Unit) {
        val old = (0 until childCount).map(::getChildAt).associateBy { it.getTag(R.id.mdm_grid_key) as String }
        val cells = items.map { item ->
            val k = key(item)
            val cell = old[k] ?: create(item).also { it.setTag(R.id.mdm_grid_key, k) }
            if (cell.getTag(R.id.mdm_grid_item) != item) {
                bind(cell, item)
                cell.setTag(R.id.mdm_grid_item, item)
            }
            cell
        }
        if (cells == (0 until childCount).map(::getChildAt)) return
        removeAllViews()
        cells.forEach(::addView)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            slotPx * GridColumns.MIN + paddingLeft + paddingRight
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val inner = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        columns = GridColumns.fit(inner, slotPx)
        cellWidth = inner / columns
        val exactWidth = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY)
        val anyHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val rows = (visibleCount() + columns - 1) / columns
        rowHeights.fill(0)
        forEachVisible { i, cell ->
            cell.measure(exactWidth, anyHeight)
            rowHeights[i / columns] = maxOf(rowHeights[i / columns], cell.measuredHeight)
        }
        forEachVisible { i, cell ->
            val rowHeight = rowHeights[i / columns]
            if (cell.measuredHeight != rowHeight) {
                cell.measure(exactWidth, MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY))
            }
        }
        var height = paddingTop + paddingBottom
        for (r in 0 until rows) height += rowHeights[r]
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        // The pixels left over after equal columns go to both sides, so the grid stays centred.
        val inset = (r - l - paddingLeft - paddingRight - cellWidth * columns) / 2
        var top = paddingTop
        forEachVisible { i, cell ->
            val column = i % columns
            if (column == 0 && i > 0) top += rowHeights[i / columns - 1]
            val left = if (rtl) {
                r - l - paddingRight - inset - (column + 1) * cellWidth
            } else {
                paddingLeft + inset + column * cellWidth
            }
            cell.layout(left, top, left + cell.measuredWidth, top + cell.measuredHeight)
        }
    }

    private fun visibleCount(): Int {
        var n = 0
        for (c in 0 until childCount) if (getChildAt(c).visibility != GONE) n++
        return n
    }

    /** [action] for each visible child with its position among the visible ones; no allocation. */
    private inline fun forEachVisible(action: (Int, View) -> Unit) {
        var i = 0
        for (c in 0 until childCount) {
            val cell = getChildAt(c)
            if (cell.visibility != GONE) action(i++, cell)
        }
    }
}

/** The grid's column count. Pure, so it is unit-tested on the JVM. */
object GridColumns {
    const val MIN = 2

    /** The icon plus 24dp, and wide enough for a label word at [fontScale] (so large fonts get fewer columns). */
    fun slotDp(iconDp: Int, fontScale: Float): Int =
        maxOf(iconDp + ICON_SIDE_SPACE_DP, Math.round(LABEL_MIN_DP * fontScale))

    private const val ICON_SIDE_SPACE_DP = 24
    private const val LABEL_MIN_DP = 80

    /** How many [slotPx]-wide columns fit in [innerPx]; never fewer than [MIN]. */
    fun fit(innerPx: Int, slotPx: Int): Int = if (slotPx <= 0) MIN else maxOf(MIN, innerPx / slotPx)
}
