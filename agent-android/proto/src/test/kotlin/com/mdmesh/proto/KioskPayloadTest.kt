package com.mdmesh.proto

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskPayloadTest {

    @Test
    fun `full payload round-trips`() {
        val p = KioskApplyPayload(
            mode = "launcher",
            allowedPackages = listOf("com.a", "com.b"),
            pinPackage = null,
            features = KioskFeaturesDto(home = true, lockButtons = false),
            exitMode = "gesture",
            password = "1234",
            theme = KioskThemeDto("#000000", "#ffffff", "SMALL"),
        )
        val json = ProtocolJson.json.encodeToString(KioskApplyPayload.serializer(), p)
        assertEquals(p, ProtocolJson.json.decodeFromString(KioskApplyPayload.serializer(), json))
    }

    @Test
    fun `minimal payload uses defaults`() {
        val p = ProtocolJson.json.decodeFromString(KioskApplyPayload.serializer(), "{}")
        assertEquals("launcher", p.mode)
        assertEquals("gesture", p.exitMode)
        assertEquals(emptyList<String>(), p.allowedPackages)
    }
}
