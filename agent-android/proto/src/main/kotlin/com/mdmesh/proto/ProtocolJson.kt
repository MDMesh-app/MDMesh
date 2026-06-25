package com.mdmesh.proto

import kotlinx.serialization.json.Json

/**
 * The single configured [Json] instance used for every protocol (de)serialization
 * on the device side.
 *
 * The configuration here is part of the wire contract, not an implementation
 * detail:
 *  - [Json.ignoreUnknownKeys] = true   — forward compatibility. A newer server may
 *    add fields to a [CommandEnvelope]; an older agent must ignore them, never crash.
 *  - [Json.encodeDefaults] = false      — keep reports lean; absent == default.
 *  - [Json.explicitNulls] = false       — omit null fields entirely rather than
 *    emitting `"field": null`, matching the "additive, unknown-tolerant" schema.
 */
object ProtocolJson {

    /** The semver-ish protocol version this agent speaks. See `proto/VERSIONING.md`. */
    const val PROTOCOL_VERSION: String = "1.0"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
}
