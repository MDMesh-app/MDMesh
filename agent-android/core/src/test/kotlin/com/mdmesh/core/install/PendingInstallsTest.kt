package com.mdmesh.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingInstallsTest {
    @Test fun `a package is pending from add until remove`() {
        val p = PendingInstalls()
        assertFalse("com.a" in p)
        p.add(listOf("com.a", "com.b"))
        assertTrue("com.a" in p)
        p.remove(listOf("com.a"))
        assertFalse("com.a" in p)
        assertEquals(setOf("com.b"), p.packages.value)
    }
}
