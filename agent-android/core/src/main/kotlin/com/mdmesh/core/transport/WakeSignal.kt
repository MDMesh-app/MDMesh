package com.mdmesh.core.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsed wake payload from the server wake channel. [kind] is "commands" (sync now) or
 * "interactive" (an admin console is open — run a brief fast-sync burst for [ttlSec]).
 * The payload never carries a command; it only tells the device to pull.
 */
data class WakeSignal(val kind: String, val ttlSec: Int? = null) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(payload: String): WakeSignal? = runCatching {
            val o = json.parseToJsonElement(payload).jsonObject
            val kind = o["wake"]?.jsonPrimitive?.content ?: return null
            WakeSignal(kind, o["ttlSec"]?.jsonPrimitive?.content?.toIntOrNull())
        }.getOrNull()
    }
}
