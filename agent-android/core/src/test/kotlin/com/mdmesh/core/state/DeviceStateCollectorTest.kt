package com.mdmesh.core.state

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceStateCollectorTest {
    @Test fun mapsBatteryPercentFromLevelAndScale() {
        // 50 of 200 scale -> 25%
        assertEquals(25, DeviceStateCollector.batteryPercent(level = 50, scale = 200))
    }

    @Test fun unknownScaleYieldsMinusOne() {
        assertEquals(-1, DeviceStateCollector.batteryPercent(level = 50, scale = 0))
    }

    @Test fun unknownLevelYieldsMinusOne() {
        assertEquals(-1, DeviceStateCollector.batteryPercent(level = -1, scale = 100))
    }
}
