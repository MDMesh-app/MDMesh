package com.mdmesh.core.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class EnrollmentHiddenAppsTest {
    private val own = "com.mdmesh.agent"

    @Test fun `the QR's list is read in order, without blanks, duplicates or malformed names`() {
        val extra = " com.tblenovo.setup, ,com.aura.oobe.lenovo,com.tblenovo.setup,not a package,x"
        assertEquals(listOf("com.tblenovo.setup", "com.aura.oobe.lenovo"), EnrollmentHiddenApps.parse(extra, own))
    }

    @Test fun `no list, or an empty one, hides nothing`() {
        assertEquals(emptyList<String>(), EnrollmentHiddenApps.parse(null, own))
        assertEquals(emptyList<String>(), EnrollmentHiddenApps.parse("", own))
    }

    @Test fun `the agent never hides itself`() {
        assertEquals(listOf("com.a.b"), EnrollmentHiddenApps.parse("com.mdmesh.agent,com.a.b", own))
    }

    @Test fun `every app is tried, and those not hidden are returned`() {
        val tried = mutableListOf<String>()
        val missed = EnrollmentHiddenApps.hideAll(listOf("com.a.hidden", "com.b.missing", "com.c.throws")) { p ->
            tried += p
            if (p == "com.c.throws") throw SecurityException("not device owner yet")
            p == "com.a.hidden"
        }
        assertEquals(listOf("com.a.hidden", "com.b.missing", "com.c.throws"), tried)
        assertEquals(listOf("com.b.missing", "com.c.throws"), missed)
    }
}
