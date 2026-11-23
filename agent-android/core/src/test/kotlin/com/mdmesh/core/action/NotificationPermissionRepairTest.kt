package com.mdmesh.core.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionRepairTest {
    private val tiramisu = 33

    @Test fun `below Android 13 nothing is needed or granted`() {
        val repair = NotificationPermissionRepair(tiramisu - 1, { true }, { false }, { error("must not grant") })
        assertTrue(repair.ensure())
    }

    @Test fun `already granted does not regrant`() {
        val repair = NotificationPermissionRepair(tiramisu, { true }, { true }, { error("must not grant") })
        assertTrue(repair.ensure())
    }

    @Test fun `owner grants a missing permission and verifies it`() {
        var granted = false
        var calls = 0
        val repair = NotificationPermissionRepair(tiramisu, { true }, { granted }, { calls++; granted = true; true })
        assertTrue(repair.ensure())
        assertTrue(repair.ensure())
        assertEquals(1, calls)
    }

    @Test fun `non owner cannot silently grant`() {
        val repair = NotificationPermissionRepair(tiramisu, { false }, { false }, { error("must not grant") })
        assertFalse(repair.ensure())
    }

    @Test fun `refused or throwing grants report missing`() {
        assertFalse(NotificationPermissionRepair(tiramisu, { true }, { false }, { false }).ensure())
        assertFalse(NotificationPermissionRepair(tiramisu, { true }, { false }, { error("denied") }).ensure())
    }
}
