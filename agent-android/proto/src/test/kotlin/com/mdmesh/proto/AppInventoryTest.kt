package com.mdmesh.proto

import org.junit.Assert.assertEquals
import org.junit.Test

class AppInventoryTest {

    @Test
    fun `scan result round-trips`() {
        val r = AppScanResult(
            apps = listOf(
                AppInfo("com.android.settings", "Settings", system = true, launchable = true, versionName = "14", versionCode = 34),
                AppInfo("com.example.app", "Example"),
            ),
        )
        val json = ProtocolJson.json.encodeToString(AppScanResult.serializer(), r)
        assertEquals(r, ProtocolJson.json.decodeFromString(AppScanResult.serializer(), json))
    }

    @Test
    fun `full scan is explicit and old scan results are not full inventory`() {
        assertEquals(false, ProtocolJson.json.decodeFromString(AppScanRequest.serializer(), "{}").includeAll)
        assertEquals(
            true, ProtocolJson.json.decodeFromString(AppScanRequest.serializer(), "{\"includeAll\":true}").includeAll,
        )
        assertEquals(false, ProtocolJson.json.decodeFromString(AppScanResult.serializer(), "{\"apps\":[]}").includeAll)
        val result = AppScanResult(
            listOf(AppInfo("com.example.hidden", "Hidden", launchable = false)), includeAll = true,
        )
        assertEquals(result, ProtocolJson.json.decodeFromString(
            AppScanResult.serializer(), ProtocolJson.json.encodeToString(AppScanResult.serializer(), result),
        ))
    }

    @Test
    fun `icons request and result round-trip`() {
        val req = AppIconsRequest(listOf("com.a", "com.b"))
        assertEquals(
            req,
            ProtocolJson.json.decodeFromString(
                AppIconsRequest.serializer(),
                ProtocolJson.json.encodeToString(AppIconsRequest.serializer(), req),
            ),
        )
        val res = AppIconsResult(listOf(AppIcon("com.a", "QUJD")))
        assertEquals(
            res,
            ProtocolJson.json.decodeFromString(
                AppIconsResult.serializer(),
                ProtocolJson.json.encodeToString(AppIconsResult.serializer(), res),
            ),
        )
    }
}
