package com.mdmesh.core.telemetry

import com.mdmesh.proto.DynamicState
import com.mdmesh.proto.HardwareInfo
import com.mdmesh.proto.IdentityInfo
import com.mdmesh.proto.SecurityPosture
import com.mdmesh.proto.TelemetrySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Composes the census from per-category producers. [dynamic] always succeeds; the other three are
 * wrapped in runCatching so a missing permission / failure contributes null (graceful degradation).
 * Producers are lambdas so this is unit-testable without Android.
 */
class TelemetryAssembler(
    private val hardware: () -> HardwareInfo,
    private val identity: () -> IdentityInfo,
    private val dynamic: () -> DynamicState,
    private val security: () -> SecurityPosture,
) : TelemetrySource {
    // A GPS fix can take seconds: collect off the caller's thread (the check-in service calls from Main).
    override suspend fun snapshot(): TelemetrySnapshot = withContext(Dispatchers.IO) {
        TelemetrySnapshot(
            dynamic = dynamic(),
            hardware = runCatching { hardware() }.getOrNull(),
            identity = runCatching { identity() }.getOrNull(),
            security = runCatching { security() }.getOrNull(),
        )
    }
}
