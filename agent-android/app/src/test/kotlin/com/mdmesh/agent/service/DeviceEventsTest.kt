package com.mdmesh.agent.service

import android.content.Intent
import com.mdmesh.proto.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceEventsTest {

    @Suppress("DEPRECATION")
    private val connectivity = android.net.ConnectivityManager.CONNECTIVITY_ACTION

    @Test
    fun `the sticky broadcast delivered on registration is not an event`() {
        // Registering for CONNECTIVITY_ACTION replays the current state at once: that is not a change.
        assertNull(DeviceEvents.eventFor(connectivity, initialSticky = true))
        assertNull(DeviceEvents.eventFor(Intent.ACTION_BATTERY_LOW, initialSticky = true))
    }

    @Test
    fun `real changes are events`() {
        assertEquals(EventType.CONNECTIVITY, DeviceEvents.eventFor(connectivity, initialSticky = false))
        assertEquals(EventType.LOW_BATTERY, DeviceEvents.eventFor(Intent.ACTION_BATTERY_LOW, initialSticky = false))
        assertNull(DeviceEvents.eventFor(Intent.ACTION_SCREEN_ON, initialSticky = false))
    }
}
