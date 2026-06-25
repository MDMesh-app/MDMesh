package com.mdmesh.policy

/**
 * A policy that is a simple on/off toggle (Wi-Fi, camera, Bluetooth, screenshots, ...).
 *
 * Toggle policies are routed generically: the `policy.apply` command handler looks one
 * up by its [capabilityKey] in the registry and calls [setEnabled] — there is no
 * per-policy `when`/switch anywhere. Adding a new toggle is: implement this interface +
 * register its factory probe in [CapabilityRegistry.togglePolicies]. Nothing else changes.
 */
interface TogglePolicy : PolicyStrategy {

    /** Apply the on/off state. Returns a [PolicyOutcome]; never throws. */
    fun setEnabled(enabled: Boolean): PolicyOutcome
}
