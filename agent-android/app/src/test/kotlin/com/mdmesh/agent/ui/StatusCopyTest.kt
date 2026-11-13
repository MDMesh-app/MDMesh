package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusCopyTest {

    private fun state(id: StatusUiState.DeviceIdUi) = StatusUiState(
        deviceOwner = true,
        deviceId = id,
        serverUrl = "https://mdm.example.com",
        kiosk = KioskCardUi.of(deviceOwner = true, locked = false, configured = false, returning = false),
        returnFailure = null,
    )

    @Test fun `copy puts the shown value on the clipboard`() {
        val clips = mutableListOf<Pair<String, String>>()
        val known = state(StatusUiState.DeviceIdUi.Known("dev_1"))
        assertEquals(true, StatusCopy.copy(known, StatusCopy.Target.DEVICE_ID) { l, t -> clips += l to t })
        assertEquals(true, StatusCopy.copy(known, StatusCopy.Target.SERVER) { l, t -> clips += l to t })
        assertEquals(listOf("Device ID" to "dev_1", "Server" to "https://mdm.example.com"), clips)
    }

    @Test fun `nothing to copy while there is no device id`() {
        val clips = mutableListOf<String>()
        for (id in listOf(StatusUiState.DeviceIdUi.Loading, StatusUiState.DeviceIdUi.Enrolling(null, null))) {
            assertEquals(false, StatusCopy.copy(state(id), StatusCopy.Target.DEVICE_ID) { _, t -> clips += t })
            assertNull(StatusCopy.textOf(state(id), StatusCopy.Target.DEVICE_ID))
        }
        assertEquals(emptyList<String>(), clips)
    }
}
