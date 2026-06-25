package com.mdmesh.remote

import com.mdmesh.proto.RemoteControlCapability
import com.mdmesh.proto.RemoteControlTier

/**
 * Determines the remote-control tier this device can advertise, feeding
 * `capabilities.remoteControl` in the [com.mdmesh.proto.CapabilityMatrix].
 *
 *  - `none`    — no screen capture available.
 *  - `view`    — MediaProjection capture available (per-session consent).
 *  - `control` — capture + input injection (the [InputInjectionService] is present
 *                and can be enabled).
 *
 * Detection currently uses injected booleans so it is unit-testable and free of
 * Android plumbing; the real probe (MediaProjection availability + accessibility
 * service presence) is wired with the capture implementation.
 */
class RemoteControlTierDetector(
    private val screenCaptureAvailable: Boolean,
    private val inputInjectionAvailable: Boolean,
) {

    fun detectTier(): String = when {
        screenCaptureAvailable && inputInjectionAvailable -> RemoteControlTier.CONTROL
        screenCaptureAvailable -> RemoteControlTier.VIEW
        else -> RemoteControlTier.NONE
    }

    fun capability(): RemoteControlCapability {
        val tier = detectTier()
        return RemoteControlCapability(
            tier = tier,
            screenCapture = screenCaptureAvailable,
            inputInjection = inputInjectionAvailable && screenCaptureAvailable,
            // Transport is empty until WebRTC/websocket signaling is implemented.
            transport = emptyList(),
        )
    }
}
