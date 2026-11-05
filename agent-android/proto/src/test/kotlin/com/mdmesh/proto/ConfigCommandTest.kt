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

    @Test
    fun `location off is advertised and decodes (protocol 1_4)`() {
        assertTrue(DeviceAction.ADVERTISED_KEYS.contains(DeviceAction.LOCATION_OFF_KEY))
        val json = """{"revision":"r","location":{"mode":"off"}}"""
        val p = ProtocolJson.json.decodeFromString(ConfigApplyPayload.serializer(), json)
        assertEquals(DeviceAction.LOCATION_OFF, p.location?.mode)
    }

    @Test
    fun `connectivity decodes and is absent by default (protocol 1_5)`() {
        val json = """{"revision":"r","connectivity":{"mode":"alwaysOn"}}"""
        val p = ProtocolJson.json.decodeFromString(ConfigApplyPayload.serializer(), json)
        assertEquals(DeviceAction.POWER_ALWAYS_ON, p.connectivity?.mode)
        val absent = ProtocolJson.json.decodeFromString(ConfigApplyPayload.serializer(), """{"revision":"r"}""")
        assertNull(absent.connectivity)
    }

    private fun systemUpdate(section: String): ConfigSystemUpdate? = ProtocolJson.json
        .decodeFromString(ConfigApplyPayload.serializer(), """{"revision":"r","systemUpdate":$section}""")
        .systemUpdate

    @Test
    fun `systemUpdate decodes each policy and is absent by default (protocol 1_6)`() {
        assertEquals(ConfigSystemUpdate(ConfigSystemUpdate.AUTOMATIC), systemUpdate("""{"policy":"automatic"}"""))
        assertEquals(
            ConfigSystemUpdate(ConfigSystemUpdate.WINDOWED, windowStart = 1320, windowEnd = 240),
            systemUpdate("""{"policy":"windowed","windowStart":1320,"windowEnd":240}"""),
        )
        assertEquals(ConfigSystemUpdate(ConfigSystemUpdate.POSTPONE), systemUpdate("""{"policy":"postpone"}"""))
        val absent = ProtocolJson.json.decodeFromString(ConfigApplyPayload.serializer(), """{"revision":"r"}""")
        assertNull(absent.systemUpdate)
        assertEquals("1.6", ProtocolJson.PROTOCOL_VERSION)
    }

    @Test
    fun `a systemUpdate the agent can't honour still decodes, so only that section is refused`() {
        // The applier reports these unsupported; failing the decode would refuse the whole document.
        assertEquals("none", systemUpdate("""{"policy":"none"}""")?.policy)
        val noTimes = systemUpdate("""{"policy":"windowed"}""")
        assertNull(noTimes?.windowStart)
        assertNull(noTimes?.windowEnd)
        assertEquals("", systemUpdate("""{"future":1}""")?.policy)
    }
}
