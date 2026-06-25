package com.mdmesh.proto

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolJsonTest {

    private val json = ProtocolJson.json

    @Test
    fun `ignores unknown keys for forward compatibility`() {
        val payload = """
            {
              "protocolVersion": "1.0",
              "commandId": "abc",
              "issuedAt": "2026-01-01T00:00:00Z",
              "type": "device.lock",
              "futureField": { "added": "by a newer server" }
            }
        """.trimIndent()

        val command = json.decodeFromString(CommandEnvelope.serializer(), payload)

        assertEquals("device.lock", command.type)
        assertEquals("abc", command.commandId)
    }

    @Test
    fun `omits null and default fields when encoding`() {
        val command = CommandEnvelope(
            commandId = "id-1",
            issuedAt = "2026-01-01T00:00:00Z",
            type = "config.sync",
        )

        val encoded = json.encodeToString(CommandEnvelope.serializer(), command)

        // explicitNulls=false + encodeDefaults=false -> these are absent.
        assertTrue("ttlSeconds" !in encoded)
        assertTrue("requiresCapability" !in encoded)
        assertTrue("payload" !in encoded)
    }

    @Test
    fun `command status serializes with registry names`() {
        val result = CommandResult(
            commandId = "id-1",
            status = CommandStatus.UNSUPPORTED,
            completedAt = "2026-01-01T00:00:00Z",
        )

        val encoded = json.encodeToString(CommandResult.serializer(), result)

        assertTrue("\"status\":\"unsupported\"" in encoded)
    }

    @Test
    fun `payload survives round trip as json object`() {
        val command = CommandEnvelope(
            commandId = "id-1",
            issuedAt = "2026-01-01T00:00:00Z",
            type = "policy.apply",
            payload = buildJsonObject {
                put("policy", "wifi")
                put("value", false)
            },
        )

        val encoded = json.encodeToString(CommandEnvelope.serializer(), command)
        val decoded = json.decodeFromString(CommandEnvelope.serializer(), encoded)

        assertEquals(command, decoded)
        assertNull(decoded.ttlSeconds)
    }
}
