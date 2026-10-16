package com.mdmesh.policy

import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the toggle policies on every call, keeping one instance per key.
 *
 * Most strategies need Device Owner, and during QR provisioning the agent process starts before it is Device
 * Owner. A map built once at that point would leave those policies out for the life of the process (#76). So
 * [current] probes again each time:
 *  - a key with no strategy yet runs its factory probe (a few cheap checks, the Device-Owner one included);
 *  - the first strategy a probe returns is kept for good, so every caller gets the same instance (a policy may
 *    keep state in memory); after that, only the instance's own [PolicyStrategy.isSupported] is checked, so a key
 *    whose support goes away (Device Owner removed) drops out of the map and comes back as the same object.
 *
 * Callers read [current] once per operation and never keep the map. Thread-safe.
 *
 * @param probes capability key -> factory probe (null = no usable strategy right now), in advertising order.
 */
class TogglePolicyResolver(private val probes: Map<String, () -> TogglePolicy?>) {

    private val resolved = ConcurrentHashMap<String, TogglePolicy>()

    /** The policies with a working strategy right now, keyed by capability key, in [probes] order. */
    fun current(): Map<String, TogglePolicy> = buildMap {
        for ((key, probe) in probes) {
            val kept = resolved[key]
            val policy = if (kept != null) {
                kept.takeIf { it.isSupported() }
            } else {
                // Two threads may probe at once: the first instance stored wins and both return it.
                probe()?.let { resolved.putIfAbsent(key, it) ?: it }
            }
            if (policy != null) put(key, policy)
        }
    }
}
