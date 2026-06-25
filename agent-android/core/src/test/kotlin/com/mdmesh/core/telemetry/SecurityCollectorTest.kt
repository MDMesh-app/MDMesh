package com.mdmesh.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecurityCollectorTest {
    // 2024-01-01 is epoch day 19723.
    @Test fun computesPatchAgeInDays() {
        assertEquals(31, SecurityCollector.patchAgeDays("2023-12-01", nowEpochDay = 19723))
    }

    @Test fun nullPatchYieldsNull() {
        assertNull(SecurityCollector.patchAgeDays(null, nowEpochDay = 19723))
    }

    @Test fun unparseablePatchYieldsNull() {
        assertNull(SecurityCollector.patchAgeDays("not-a-date", nowEpochDay = 19723))
    }
}
