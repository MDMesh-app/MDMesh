package com.mdmesh.core.command

import com.mdmesh.core.action.AlertSink
import com.mdmesh.core.command.handlers.DeviceAlertHandler
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAlertHandlerTest {

    private class FakeAlerts(private val canShow: Boolean) : AlertSink {
        val shown = mutableListOf<Pair<String, String>>()
        override fun show(title: String, body: String): Boolean {
            if (canShow) shown += title to body
            return canShow
        }
    }

    private fun command(payload: JsonObject?) = CommandEnvelope(
        commandId = "c1",
        issuedAt = "2026-01-01T00:00:00Z",
        type = "device.alert",
        payload = payload,
    )

    @Test
    fun `shows the alert and reports done`() = runTest {
        val alerts = FakeAlerts(canShow = true)
        val payload = buildJsonObject {
            put("title", "IT")
            put("body", "Hello")
        }
        val result = DeviceAlertHandler(alerts).handle(command(payload))

        assertEquals(CommandStatus.DONE, result.status)
        assertEquals(listOf("IT" to "Hello"), alerts.shown)
    }

    @Test
    fun `uses the default title when none is given`() = runTest {
        val alerts = FakeAlerts(canShow = true)
        DeviceAlertHandler(alerts).handle(command(buildJsonObject { put("body", "Hello") }))

        assertEquals(listOf("Message from IT" to "Hello"), alerts.shown)
    }

    @Test
    fun `reports failed when the device can't show the alert`() = runTest {
        val result = DeviceAlertHandler(FakeAlerts(canShow = false)).handle(
            command(buildJsonObject { put("body", "Hello") }),
        )

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(result.detail.orEmpty().contains("notifications are disabled"))
    }

    @Test
    fun `reports failed when the payload is missing`() = runTest {
        val result = DeviceAlertHandler(FakeAlerts(canShow = true)).handle(command(payload = null))
        assertEquals(CommandStatus.FAILED, result.status)
    }
}
