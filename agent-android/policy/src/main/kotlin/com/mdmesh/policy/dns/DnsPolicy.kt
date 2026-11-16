package com.mdmesh.policy.dns

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.PolicyStrategy

/**
 * Capability-abstracted DNS policy control.
 *
 * Configures global private DNS mode and host on the device.
 * Backed by [android.app.admin.DevicePolicyManager.setGlobalPrivateDnsModeSpecifiedHost]
 * or [android.app.admin.DevicePolicyManager.setGlobalPrivateDnsModeOpportunistic].
 */
interface DnsPolicy : PolicyStrategy {

    /**
     * Set global private DNS to the specified host/IP ([dnsHost]), or clear it / set
     * to opportunistic mode if [dnsHost] is null or blank.
     */
    fun setDnsHost(dnsHost: String?): PolicyOutcome

    companion object {
        const val CAPABILITY_KEY = "dns"
    }
}
