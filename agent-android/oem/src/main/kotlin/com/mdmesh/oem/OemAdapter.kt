package com.mdmesh.oem

import com.mdmesh.proto.OemCapability

/**
 * Pluggable OEM-extension surface.
 *
 * Some vendors (Samsung Knox, Zebra MX, ...) expose privileged controls beyond
 * AOSP's [android.app.admin.DevicePolicyManager]. The agent stays vendor-neutral
 * by routing everything through this interface and selecting an implementation at
 * startup. The default fleet ships [GenericOemAdapter] (pure AOSP, no extra
 * privileges); vendor adapters are added behind a build flavor / runtime probe so
 * the base APK carries no proprietary dependency.
 */
interface OemAdapter {

    /** Short vendor tag advertised in the matrix, e.g. `generic`, `samsung`. */
    val vendor: String

    /** Describes the OEM capabilities for the [com.mdmesh.proto.CapabilityMatrix]. */
    fun capability(): OemCapability

    /** True if this adapter's privileged stack is actually available on the device. */
    fun isAvailable(): Boolean
}
