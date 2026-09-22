package com.mdmesh.proto

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCommandTest {
    @Test
    fun `decodes a full document and tolerates unknown keys`() {
        val json = """{"revision":"r1","configurationId":12,"policies":{"wifi":true,"screenshots":false},
            "kiosk":{"mode":"single","pinPackage":"com.acme.pos","allowedPackages":["com.acme.pos"],"future":1},
            "location":{"mode":"active"},"future":true}"""
        val p = ProtocolJson.json.decodeFromString(ConfigApplyPayload.serializer(), json)
        assertEquals("r1", p.revision)
        assertEquals(mapOf("wifi" to true, "screenshots" to false), p.policies)
        assertEquals("com.acme.pos", p.kiosk?.pinPackage)
        assertEquals("active", p.location?.mode)
    }

    @Test
    fun `kiosk absent decodes to null`() {
        val p = ProtocolJson.json.decodeFromString(ConfigApplyPayload.serializer(), """{"revision":"r","configurationId":1}""")
        assertNull(p.kiosk)
        assertNull(p.location)
    }

    @Test
    fun `result encodes outcomes map`() {
        val s = ProtocolJson.json.encodeToString(ConfigApplyResult("r1", mapOf("policies.wifi" to ConfigOutcome.APPLIED, "kiosk" to ConfigOutcome.failed("nope"))))
        assertTrue(s.contains("\"policies.wifi\":\"applied\""))
        assertTrue(s.contains("\"kiosk\":\"failed: nope\""))
    }

    @Test
    fun `configApply is advertised and state carries appliedConfigRevision`() {
        assertTrue(DeviceAction.ADVERTISED_KEYS.contains(DeviceAction.CONFIG_APPLY_KEY))
        val dto = AgentDeviceStateDto(battery = 1, charging = false, locked = false, kioskActive = false, androidRelease = "14", lastBootAt = 0L, appliedConfigRevision = "r1")
        assertTrue(ProtocolJson.json.encodeToString(dto).contains("\"appliedConfigRevision\":\"r1\""))
    }
}
