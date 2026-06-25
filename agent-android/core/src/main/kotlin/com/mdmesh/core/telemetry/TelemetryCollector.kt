package com.mdmesh.core.telemetry

import com.mdmesh.proto.TelemetrySnapshot

/** Builds the device census snapshot from the available collectors. Null only if even the
 *  always-available dynamic state can't be built (it always can). */
fun interface TelemetrySource {
    fun snapshot(): TelemetrySnapshot?
}
