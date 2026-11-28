package com.mdmesh.core.kiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentDebuggingTest {
    @Test fun `only an explicit true turns debugging on`() {
        assertTrue(EnrollmentDebugging.asked("true"))
        assertTrue(EnrollmentDebugging.asked(" TRUE "))
        assertFalse(EnrollmentDebugging.asked(null))
        assertFalse(EnrollmentDebugging.asked(""))
        assertFalse(EnrollmentDebugging.asked("yes"))
        assertFalse(EnrollmentDebugging.asked("false"))
    }
}
