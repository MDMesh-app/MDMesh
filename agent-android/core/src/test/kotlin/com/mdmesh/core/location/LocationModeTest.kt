package com.mdmesh.core.location

import com.mdmesh.proto.DeviceAction
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationModeTest {
    @Test fun `off is kept, unknown values fall back to passive`() {
        assertEquals(DeviceAction.LOCATION_OFF, LocationModeStore.normalize("off"))
        assertEquals(DeviceAction.LOCATION_ACTIVE, LocationModeStore.normalize("active"))
        assertEquals(DeviceAction.LOCATION_PASSIVE, LocationModeStore.normalize("passive"))
        assertEquals(DeviceAction.LOCATION_PASSIVE, LocationModeStore.normalize("somethingNew"))
        assertEquals(DeviceAction.LOCATION_PASSIVE, LocationModeStore.normalize(null))
    }

    @Test fun `off reads no location at all, passive reads last-known, active asks for a fresh fix`() {
        assertEquals(LocationCollector.Plan.NONE, LocationCollector.plan(DeviceAction.LOCATION_OFF))
        assertEquals(LocationCollector.Plan.LAST_KNOWN, LocationCollector.plan(DeviceAction.LOCATION_PASSIVE))
        assertEquals(LocationCollector.Plan.FRESH_FIX, LocationCollector.plan(DeviceAction.LOCATION_ACTIVE))
    }
}
