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
 * A probe or support check that throws (e.g. a DevicePolicyManager binder error) is not "unsupported": the key goes
 * to [ResolvedToggles.errors] for this call, so callers fail closed for that key alone (report it failed and retry)
 * and the other keys are unaffected. It is reported to [onProbeError] and tried again on the next call.
 *
 * Callers read [current] once per operation and never keep the result. Thread-safe.
 *
 * @param probes capability key -> factory probe (null = no usable strategy right now), in advertising order.
 * @param onProbeError told about a key whose probe or support check threw (e.g. [ProbeErrorLog.failed]).
 * @param onProbeOk told about a key whose probe and support check returned normally (e.g. [ProbeErrorLog.recovered]).
 */
class TogglePolicyResolver(
    private val probes: Map<String, () -> TogglePolicy?>,
    private val onProbeError: (key: String, error: Throwable) -> Unit = { _, _ -> },
    private val onProbeOk: (key: String) -> Unit = {},
) {

    private val resolved = ConcurrentHashMap<String, TogglePolicy>()

    /** The policies with a working strategy right now, and the keys whose check threw, in [probes] order. */
    fun current(): ResolvedToggles {
        val policies = linkedMapOf<String, TogglePolicy>()
        val errors = linkedMapOf<String, ProbeError>()
        val advertised = mutableListOf<String>()
        for ((key, probe) in probes) {
            runCatching { resolve(key, probe) }
                .onSuccess { policy ->
                    onProbeOk(key)
                    if (policy != null) {
                        policies[key] = policy
                        advertised += key
                    }
                }
                .onFailure { error ->
                    val kept = resolved[key]
                    errors[key] = ProbeError(error, kept)
                    // Still advertised when it worked before: the server keeps sending it and the agent keeps
                    // retrying it. A key that never resolved is not advertised (it may be unsupported here).
                    if (kept != null) advertised += key
                    onProbeError(key, error)
                }
        }
        return ResolvedToggles(policies, errors, advertised)
    }

    private fun resolve(key: String, probe: () -> TogglePolicy?): TogglePolicy? {
        val kept = resolved[key]
        return if (kept != null) {
            kept.takeIf { it.isSupported() }
        } else {
            // Two threads may probe at once: the first instance stored wins and both return it.
            probe()?.let { resolved.putIfAbsent(key, it) ?: it }
        }
    }
}

/** A key whose probe or support check threw on this read. [kept]: its strategy, when it resolved before. */
class ProbeError(val error: Throwable, val kept: TogglePolicy?) {
    /** The outcome reason reported for the key: retryable, and names the exception without its message. */
    val reason: String get() = "policy check error (${error.javaClass.simpleName})"
}

/**
 * One read of the toggle policies ([TogglePolicyResolver.current]).
 *
 * @param policies usable now, keyed by capability key.
 * @param errors keys whose probe or support check threw this time. Not unsupported: callers report them failed
 *   (so they are retried) and never apply or release them.
 * @param advertisedKeys what the capability matrix advertises: [policies], plus errored keys that resolved before.
 */
class ResolvedToggles(
    val policies: Map<String, TogglePolicy>,
    val errors: Map<String, ProbeError> = emptyMap(),
    val advertisedKeys: List<String> = policies.keys.toList(),
) {
    operator fun get(key: String): TogglePolicy? = policies[key]

    operator fun contains(key: String): Boolean = key in policies
}
