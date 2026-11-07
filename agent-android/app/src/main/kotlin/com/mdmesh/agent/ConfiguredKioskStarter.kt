package com.mdmesh.agent

import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload

/**
 * Testable boundary for the status-screen recovery action. It deliberately owns only kiosk entry:
 * callers provide the saved payload and no complete configuration apply is involved.
 */
class ConfiguredKioskStarter(
    private val enter: suspend (KioskApplyPayload) -> KioskResult,
    private val recordStart: () -> Unit,
    private val requestCheckIn: () -> Unit,
) {
    suspend fun start(payload: KioskApplyPayload?): KioskResult? {
        if (payload == null) return null
        return enter(payload).also { result ->
            if (result == KioskResult.Ok) {
                recordStart()
                requestCheckIn()
            }
        }
    }
}
