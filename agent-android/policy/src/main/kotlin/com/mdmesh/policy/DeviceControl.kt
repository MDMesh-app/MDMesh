package com.mdmesh.policy

import com.mdmesh.policy.wifi.WifiPolicy

/**
 * The narrow, *capability-abstraction* surface the rest of the agent is allowed to
 * touch. Every concrete `DevicePolicyManager` call lives behind one of these
 * operations, never in feature code.
 *
 * ### Why this exists
 * Android is a permanent version treadmill: each OS release adds, removes, or
 * restricts a policy API. If feature code called `DevicePolicyManager` directly it
 * would be riddled with `Build.VERSION.SDK_INT` branches and would crash on
 * devices where an API is missing. Instead:
 *
 *  1. Feature code asks for a high-level operation (e.g. "set Wi-Fi enabled").
 *  2. A [PolicyStrategy] / factory picks the right implementation for *this* SDK.
 *  3. If no implementation exists, the operation is simply never advertised in the
 *     [com.mdmesh.proto.CapabilityMatrix], so the server never asks for it.
 *
 * Operations return [PolicyOutcome] rather than throwing, so a degraded device
 * reports `unsupported`/`failed` instead of crashing.
 */
interface DeviceControl {

    /** Wi-Fi enable/disable + admin lock. The one worked example in this scaffold. */
    val wifi: WifiPolicy

    // Future policy surfaces (bluetooth, camera, screenshots, kiosk, ...) are added
    // here as additional capability-abstracted interfaces, each with their own
    // SDK_INT-gated strategies.
}

/** Result of a policy operation. Never throw across the abstraction boundary. */
sealed interface PolicyOutcome {
    data object Applied : PolicyOutcome

    /** The device cannot do this at all (API absent / not Device Owner). */
    data object Unsupported : PolicyOutcome

    data class Failed(val reason: String) : PolicyOutcome
}
