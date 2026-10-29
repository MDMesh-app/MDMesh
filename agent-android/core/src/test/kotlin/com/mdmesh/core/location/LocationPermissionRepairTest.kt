package com.mdmesh.core.location

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationPermissionRepairTest {
    private val permissions = listOf("fine", "coarse", "background")

    @Test fun `upgrade repairs missing background and does not regrant existing permissions`() {
        val granted = mutableSetOf("fine", "coarse")
        val calls = mutableListOf<String>()
        val repair = LocationPermissionRepair({ true }, granted::contains, { calls.add(it); granted.add(it) })
        assertEquals(emptyList<String>(), repair.ensure(permissions))
        assertEquals(listOf("background"), calls)
        repair.ensure(permissions)
        assertEquals(listOf("background"), calls)
    }

    @Test fun `fresh grants are ordered foreground before background`() {
        val granted = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        val repair = LocationPermissionRepair({ true }, granted::contains, { calls.add(it); granted.add(it) })
        assertEquals(emptyList<String>(), repair.ensure(permissions))
        assertEquals(permissions, calls)
    }

    @Test fun `non owner cannot silently grant permissions`() {
        val repair = LocationPermissionRepair({ false }, { false }, { error("must not grant") })
        assertEquals(permissions, repair.ensure(permissions))
    }

    @Test fun `refused or throwing grants remain missing and retry next time`() {
        var calls = 0
        val repair = LocationPermissionRepair({ true }, { false }, {
            calls++
            check(it != "fine") { "denied" }
            false
        })
        assertEquals(permissions, repair.ensure(permissions))
        assertEquals(permissions, repair.ensure(permissions))
        assertEquals(6, calls)
    }
}
