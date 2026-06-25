package com.mdmesh.policy

/**
 * Strategy pattern for SDK-gated policy implementations.
 *
 * A policy surface (e.g. [WifiPolicy]) usually has more than one viable
 * implementation across the fleet: a modern API on newer Android, a legacy
 * fallback on older Android, and possibly an OEM-specific path. Each candidate is
 * a [PolicyStrategy]; a factory selects the first one whose [isSupported] returns
 * true on the current device.
 *
 * Keeping the `Build.VERSION.SDK_INT` test inside [isSupported] — rather than
 * scattered through call sites — is the whole point: selection happens once, in
 * one place, and the chosen strategy is then used unconditionally.
 */
interface PolicyStrategy {

    /** Stable capability key advertised in the matrix (see `proto/registry.md`). */
    val capabilityKey: String

    /**
     * Whether this strategy can run on the current device.
     * Typically a `Build.VERSION.SDK_INT >= ...` check plus a Device-Owner check.
     */
    fun isSupported(): Boolean
}
