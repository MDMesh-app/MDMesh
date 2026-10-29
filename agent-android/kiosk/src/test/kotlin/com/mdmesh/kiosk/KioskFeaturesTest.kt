package com.mdmesh.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Asserts against the real `LOCK_TASK_FEATURE_*` integer values (compile-time constants,
 * inlined by the Kotlin compiler): SYSTEM_INFO=1, NOTIFICATIONS=2, HOME=4, OVERVIEW=8,
 * GLOBAL_ACTIONS=16, KEYGUARD=32.
 */
class KioskFeaturesTest {

    @Test
    fun `all null yields global actions only`() {
        assertEquals(16, lockTaskFeatures(KioskToggles()))
    }

    @Test
    fun `enabling toggles adds their flags plus global actions`() {
        val f = lockTaskFeatures(
            KioskToggles(home = true, recents = true, notifications = true, systemInfo = true, keyguard = true),
        )
        // 4 | 8 | 2 | 1 | 32 | 16 (global actions still on, lockButtons not set)
        assertEquals(63, f)
    }

    @Test
    fun `recents and notifications need home`() {
        // Android refuses OVERVIEW or NOTIFICATIONS without HOME, which would fail the whole kiosk entry.
        assertEquals(16, lockTaskFeatures(KioskToggles(recents = true, notifications = true)))
        assertEquals(16, lockTaskFeatures(KioskToggles(home = false, recents = true, notifications = true)))
        // 4 | 8 | 2 | 16
        assertEquals(30, lockTaskFeatures(KioskToggles(home = true, recents = true, notifications = true)))
    }

    @Test
    fun `lockButtons removes the power menu`() {
        assertEquals(0, lockTaskFeatures(KioskToggles(lockButtons = true)))
        // with other toggles, global actions is still removed
        assertEquals(4, lockTaskFeatures(KioskToggles(home = true, lockButtons = true)))
    }
}
