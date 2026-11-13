package com.mdmesh.agent.ui

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskAppLoaderTest {

    @Test fun `the icon cache key changes with the app's update and the display density`() {
        val base = KioskAppLoader.iconKey("com.example", updated = 1L, densityDpi = 420)
        assertNotEquals(base, KioskAppLoader.iconKey("com.example", updated = 2L, densityDpi = 420))
        assertNotEquals(base, KioskAppLoader.iconKey("com.example", updated = 1L, densityDpi = 480))
        assertNotEquals(base, KioskAppLoader.iconKey("com.other", updated = 1L, densityDpi = 420))
    }

    @Test fun `a package change reloads the grid only when it shows that package`() {
        val grid = KioskScreen.Grid(KioskPalette.DEFAULT, KioskExit.NONE, IconSize.SMALL, listOf("a", "b"), null)
        assertTrue(KioskAppLoader.affects(grid, "a"))
        assertTrue(!KioskAppLoader.affects(grid, "c"))
        assertTrue(!KioskAppLoader.affects(KioskScreen.Idle, "a"))
        assertTrue(!KioskAppLoader.affects(KioskScreen.Hold(KioskPalette.DEFAULT, KioskExit.NONE), "a"))
    }

    @Test fun `the cache is bounded in bytes`() {
        assertTrue(KioskAppLoader.cacheBytes(memoryClassMb = 64) in 1..(64 * 1024 * 1024 / 8))
        assertTrue(KioskAppLoader.cacheBytes(memoryClassMb = 512) <= 16 * 1024 * 1024)
    }
}
