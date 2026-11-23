package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskScreenTest {

    @Test fun `no exit PIN means no on-device exit, whatever the mode`() {
        for (mode in listOf("visible", "gesture", "remote", null)) {
            assertEquals(KioskExit.NONE, KioskExit.of(mode, pinAvailable = false))
        }
    }

    @Test fun `exit modes with a PIN`() {
        assertEquals(KioskExit.VISIBLE, KioskExit.of("visible", true))
        assertEquals(KioskExit.GESTURE, KioskExit.of("gesture", true))
        assertEquals(KioskExit.NONE, KioskExit.of("remote", true))
        assertEquals(KioskExit.NONE, KioskExit.of(null, true))
        assertEquals(KioskExit.NONE, KioskExit.of("VISIBLE", true)) // as before: exact match
    }

    @Test fun `icon sizes, unknown is small`() {
        assertEquals(IconSize.LARGE, IconSize.of("large"))
        assertEquals(IconSize.MEDIUM, IconSize.of("MEDIUM"))
        assertEquals(IconSize.SMALL, IconSize.of("SMALL"))
        assertEquals(IconSize.SMALL, IconSize.of(null))
        assertEquals(IconSize.SMALL, IconSize.of("huge"))
        assertEquals(listOf(56, 72, 96), IconSize.entries.map { it.dp })
    }

    @Test fun `idle is the default palette with no exit`() {
        assertEquals(KioskPalette.DEFAULT, KioskScreen.Idle.palette)
        assertEquals(KioskExit.NONE, KioskScreen.Idle.exit)
    }

    @Test fun `a grid differs from its loaded self only in apps`() {
        val loading = KioskScreen.Grid(KioskPalette.DEFAULT, KioskExit.VISIBLE, IconSize.SMALL, listOf("a"), null)
        val loaded = loading.copy(apps = emptyList())
        assertEquals(loading, loaded.copy(apps = null))
    }
}
