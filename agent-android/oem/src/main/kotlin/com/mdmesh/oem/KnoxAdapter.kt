package com.mdmesh.oem

import com.mdmesh.proto.OemCapability

/**
 * PARKED. Samsung Knox adapter stub.
 *
 * Intentionally carries **no** Knox SDK dependency: pulling in the proprietary
 * Knox jar bloats the base APK and trips Play Protect heuristics, so it stays out
 * of the default fleet until there is a concrete Knox requirement. When revived,
 * this moves behind a `knox` build flavor and reflectively probes for the Knox
 * runtime; [isAvailable] returns false here so it is never selected.
 */
class KnoxAdapter : OemAdapter {

    override val vendor: String = "samsung"

    override fun capability(): OemCapability = OemCapability(vendor = vendor, knox = false)

    // PARKED: no Knox SDK linked, so the privileged stack is never present.
    override fun isAvailable(): Boolean = false
}
