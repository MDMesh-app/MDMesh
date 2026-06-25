package com.mdmesh.core.telemetry

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicStateCollectorTest {
    @Test fun batteryPercent() {
        assertEquals(25, DynamicStateCollector.batteryPercent(level = 50, scale = 200))
        assertEquals(-1, DynamicStateCollector.batteryPercent(level = 50, scale = 0))
    }

    @Test fun chargingSourceMapping() {
        assertEquals("ac", DynamicStateCollector.chargingSource(BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals("usb", DynamicStateCollector.chargingSource(BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals("wireless", DynamicStateCollector.chargingSource(BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertEquals("none", DynamicStateCollector.chargingSource(0))
    }
}
