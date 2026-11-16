package com.mdmesh.policy.dns

import android.app.admin.DevicePolicyManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.wifi.DpmHandle

/**
 * Global Private DNS policy strategy for Android 10+ (API 29+).
 *
 * Uses [DevicePolicyManager.setGlobalPrivateDnsModeSpecifiedHost] when a hostname/IP is provided,
 * or [DevicePolicyManager.setGlobalPrivateDnsModeOpportunistic] when null/blank.
 */
internal class PrivateDnsPolicy(
    private val handle: DpmHandle,
) : DnsPolicy {

    override val capabilityKey: String = DnsPolicy.CAPABILITY_KEY

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun setDnsHost(dnsHost: String?): PolicyOutcome {
        if (!isSupported()) return PolicyOutcome.Unsupported

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return applyDnsHost(dnsHost)
        }

        return PolicyOutcome.Unsupported
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun applyDnsHost(dnsHost: String?): PolicyOutcome {
        val host = dnsHost?.trim()
        return runCatching {
            if (!host.isNullOrEmpty()) {
                val resultCode = handle.dpm.setGlobalPrivateDnsModeSpecifiedHost(handle.admin, host)
                if (resultCode == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    PolicyOutcome.Applied
                } else {
                    PolicyOutcome.Failed("setGlobalPrivateDnsModeSpecifiedHost returned code $resultCode")
                }
            } else {
                val resultCode = handle.dpm.setGlobalPrivateDnsModeOpportunistic(handle.admin)
                if (resultCode == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    PolicyOutcome.Applied
                } else {
                    PolicyOutcome.Failed("setGlobalPrivateDnsModeOpportunistic returned code $resultCode")
                }
            }
        }.getOrElse { PolicyOutcome.Failed(it.message ?: "setDnsHost failed") }
    }
}
