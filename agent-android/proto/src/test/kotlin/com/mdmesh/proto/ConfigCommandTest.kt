package com.mdmesh.proto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `app permissions distinguish an old server omission from an explicit release (protocol 1_7)`() {
        val managedJson = """{"revision":"r","appPermissions":[{"packageName":"org.example.maps",""" +
            """"permissions":{"android.permission.CAMERA":"deny"}}]}"""
        val managed = ProtocolJson.json.decodeFromString(ConfigApplyPayload.serializer(), managedJson)
        assertEquals("deny", managed.appPermissions?.single()?.permissions?.get("android.permission.CAMERA"))
        val omitted = ProtocolJson.json.decodeFromString(ConfigApplyPayload.serializer(), """{"revision":"r"}""")
        assertNull(omitted.appPermissions)
        assertEquals(emptyList<ConfigAppPermissions>(),
            ProtocolJson.json.decodeFromString(
                ConfigApplyPayload.serializer(),
                """{"revision":"r","appPermissions":[]}""",
            ).appPermissions,
        )
        assertEquals("1.11", ProtocolJson.PROTOCOL_VERSION)
    }

    @Test
    fun `app restrictions decode every type and keep an unknown one (protocol 1_8)`() {
        val json = """{"revision":"r","appRestrictions":[{"packageName":"hr.example.tablet","future":1,"values":{""" +
            """"kioskMode":{"type":"bool","value":true},"pollSeconds":{"type":"int","value":30},""" +
            """"serverUrl":{"type":"string","value":"https://vhp.example.hr","future":true},""" +
            """"nested":{"type":"bundle","value":{"a":1}}}}]}"""
        val entry = ProtocolJson.json.decodeFromString(ConfigApplyPayload.serializer(), json).appRestrictions!!.single()
        assertEquals("hr.example.tablet", entry.packageName)
        assertEquals(
            ConfigRestrictionValue(ConfigRestrictionValue.BOOL, JsonPrimitive(true)),
            entry.values["kioskMode"],
        )
        assertEquals(ConfigRestrictionValue(ConfigRestrictionValue.INT, JsonPrimitive(30)), entry.values["pollSeconds"])
        assertEquals(
            ConfigRestrictionValue(ConfigRestrictionValue.STRING, JsonPrimitive("https://vhp.example.hr")),
            entry.values["serverUrl"],
        )
        assertEquals("bundle", entry.values["nested"]?.type)
    }

    @Test
    fun `app restrictions distinguish an old server omission from an explicit clear (protocol 1_8)`() {
        val omitted = ProtocolJson.json.decodeFromString(ConfigApplyPayload.serializer(), """{"revision":"r"}""")
        assertNull(omitted.appRestrictions)
        val cleared = ProtocolJson.json.decodeFromString(
            ConfigApplyPayload.serializer(),
            """{"revision":"r","appRestrictions":[]}""",
        )
        assertEquals(emptyList<ConfigAppRestrictions>(), cleared.appRestrictions)
        assertTrue(AppManagement.DEVICE_OWNER_KEYS.contains(AppManagement.MANAGED_CONFIG))
    }

    @Test
    fun `a restriction value never prints its value`() {
        val secret = ConfigRestrictionValue(ConfigRestrictionValue.STRING, JsonPrimitive("device-token-123"))
        assertFalse(ConfigAppRestrictions("p", mapOf("deviceToken" to secret)).toString().contains("device-token-123"))
    }
}
