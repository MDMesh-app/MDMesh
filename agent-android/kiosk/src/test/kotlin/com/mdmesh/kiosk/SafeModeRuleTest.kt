package com.mdmesh.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeModeRuleTest {

    @Test
    fun `kiosk blocks Safe Mode unless the configuration allows it`() {
        assertEquals(true, safeModeBlocked(inKiosk = true, policy = null))
        assertEquals(true, safeModeBlocked(inKiosk = true, policy = false))
        assertEquals("an explicit Allow wins", false, safeModeBlocked(inKiosk = true, policy = true))
    }

    @Test
    fun `outside kiosk only an explicit Block blocks it`() {
        assertEquals(true, safeModeBlocked(inKiosk = false, policy = false))
        assertEquals("not managed really means not managed", false, safeModeBlocked(inKiosk = false, policy = null))
        assertEquals(false, safeModeBlocked(inKiosk = false, policy = true))
    }
}
