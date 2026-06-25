package com.mdmesh.oem

import com.mdmesh.proto.OemCapability

/**
 * Default no-op adapter: pure AOSP, no vendor privileges. Always available, so it
 * is the safe fallback when no vendor adapter matches.
 */
class GenericOemAdapter : OemAdapter {

    override val vendor: String = "generic"

    override fun capability(): OemCapability = OemCapability(vendor = vendor, knox = false)

    override fun isAvailable(): Boolean = true
}
