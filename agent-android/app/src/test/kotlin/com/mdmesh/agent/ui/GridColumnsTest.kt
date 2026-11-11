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
}
