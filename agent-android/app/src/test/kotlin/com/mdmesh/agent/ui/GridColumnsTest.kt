package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GridColumnsTest {

    private fun cols(screenDp: Int, iconDp: Int, fontScale: Float = 1f): Int {
        val inner = screenDp - 16 // 8dp grid padding on each side on a phone
        return GridColumns.fit(inner, GridColumns.slotDp(iconDp, fontScale))
    }

    @Test fun `phone portrait matches the old grid`() {
        // The pre-redesign grid at 411dp and 360dp wide: small 4, medium 4 / 3, large 3 / 2.
        assertEquals(4, cols(411, IconSize.SMALL.dp))
        assertEquals(4, cols(411, IconSize.MEDIUM.dp))
        assertEquals(3, cols(411, IconSize.LARGE.dp))
        assertEquals(4, cols(360, IconSize.SMALL.dp))
        assertEquals(3, cols(360, IconSize.MEDIUM.dp))
        assertEquals(2, cols(360, IconSize.LARGE.dp))
    }

    @Test fun `rotating to landscape adds columns`() {
        assertEquals(10, cols(891, IconSize.SMALL.dp))
        assertEquals(7, cols(891, IconSize.LARGE.dp))
    }

    @Test fun `large fonts get fewer, wider columns`() {
        assertEquals(80, GridColumns.slotDp(IconSize.SMALL.dp, 1f))
        assertEquals(120, GridColumns.slotDp(IconSize.LARGE.dp, 1f))
        assertEquals(3, cols(360, IconSize.SMALL.dp, 1.3f))
        assertEquals(2, cols(360, IconSize.SMALL.dp, 2f))
        assertEquals(3, cols(411, IconSize.LARGE.dp, 1.3f))
    }

    @Test fun `never fewer than two`() {
        assertEquals(GridColumns.MIN, GridColumns.fit(10, 120))
        assertEquals(GridColumns.MIN, GridColumns.fit(0, 120))
        assertEquals(GridColumns.MIN, GridColumns.fit(500, 0))
    }

    @Test fun `the header starts where the first icon starts, at any width`() {
        // Header start = first icon's left edge, from the same numbers the grid lays out with.
        val density = 2.625f
        fun px(dp: Int) = Math.round(dp * density)
        for (widthDp in listOf(320, 360, 411, 600, 891)) for (size in IconSize.entries) {
            val width = px(widthDp)
            val pad = px(8)
            val icon = px(size.dp)
            val slot = px(GridColumns.slotDp(size.dp, 1f))
            val start = GridColumns.iconStart(width, pad, pad, slot, icon)
            val inner = width - 2 * pad
            val cols = GridColumns.fit(inner, slot)
            val cell = inner / cols
            val cellLeft = GridColumns.cellLeft(0, width, pad, pad, slot)
            assertEquals("$widthDp dp $size", cellLeft + (cell - icon) / 2, start)
            assertEquals(pad + (inner - cell * cols) / 2, cellLeft)
        }
    }
}
