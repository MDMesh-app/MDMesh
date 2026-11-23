/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class EnrollmentTokenLimitsTest {

    private static final long HOUR = 60L * 60 * 1000;

    @Test
    public void aTokenLeftAtItsDefaultsIsSingleUseForADay() {
        // What every caller got before tokens could be reused: nothing changes for them.
        assertEquals(1, EnrollmentTokenLimits.uses(null));
        assertEquals(24 * HOUR, EnrollmentTokenLimits.validMillis(null));
        assertNull(EnrollmentTokenLimits.problem(null, null));
    }

    @Test
    public void aTokenMayEnrollSeveralDevicesForSeveralDays() {
        assertEquals(25, EnrollmentTokenLimits.uses(25));
        assertEquals(72 * HOUR, EnrollmentTokenLimits.validMillis(72));
        assertNull(EnrollmentTokenLimits.problem(25, 72));
        assertNull(EnrollmentTokenLimits.problem(EnrollmentTokenLimits.MAX_USES, EnrollmentTokenLimits.MAX_VALID_HOURS));
    }

    @Test
    public void usesOutsideTheLimitsAreRefused() {
        assertNotNull(EnrollmentTokenLimits.problem(0, null));
        assertNotNull(EnrollmentTokenLimits.problem(-1, null));
        assertNotNull(EnrollmentTokenLimits.problem(EnrollmentTokenLimits.MAX_USES + 1, null));
    }

    @Test
    public void validityOutsideTheLimitsIsRefused() {
        assertNotNull(EnrollmentTokenLimits.problem(null, 0));
        assertNotNull(EnrollmentTokenLimits.problem(null, EnrollmentTokenLimits.MAX_VALID_HOURS + 1));
    }
}
