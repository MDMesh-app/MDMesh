package com.mdmesh.agent

import com.mdmesh.core.sync.EnrollmentFailure
import com.mdmesh.core.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIdLabelTest {

    private val time: (Long) -> String = { "10:00" }

    @Test
    fun `lost credentials show next to the id, whatever the last check-in reported`() {
        // As production shows it: the recovery gave up, then the next check-in was refused and replaced the last error.
        val later = SyncStatus.Failure("error.agent.unauthorized", 2L)

        val shown = DeviceIdLabel.of("dev-1", credentialsLost = true, lastError = later, time = time)

        assertEquals("dev-1\n${EnrollmentFailure.CREDENTIALS_LOST}", shown)
    }

    @Test
    fun `an enrolled device with working credentials shows its id`() {
        assertEquals("dev-1", DeviceIdLabel.of("dev-1", credentialsLost = false, lastError = null, time = time))
    }

    @Test
    fun `before enrollment it says so, with the last error when there is one`() {
        assertEquals("Enrolling…", DeviceIdLabel.of(null, credentialsLost = false, lastError = null, time = time))
        assertEquals(
            "Enrolling… (last error: no code at 10:00)",
            DeviceIdLabel.of("", credentialsLost = false, lastError = SyncStatus.Failure("no code", 1L), time = time),
        )
    }
}
