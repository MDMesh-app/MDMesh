package com.mdmesh.policy

/**
 * A policy that is a simple on/off toggle (Wi-Fi, camera, Bluetooth, screenshots, ...).
 *
 * Toggle policies are routed generically: the `policy.apply` and `config.apply` handlers look
 * one up by its [capabilityKey] in [CapabilityRegistry.togglePolicies] and call [setEnabled] —
 * there is no per-policy `when`/switch anywhere. Adding a new toggle is: implement this
 * interface + add its factory probe to the registry's probe map (the [TogglePolicyResolver] in
 * [CapabilityRegistry]). Nothing else changes.
 */
interface TogglePolicy : PolicyStrategy {

    /** Apply the on/off state. Returns a [PolicyOutcome]; never throws. */
    fun setEnabled(enabled: Boolean): PolicyOutcome
}
