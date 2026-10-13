package com.mdmesh.policy.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.ReleasablePolicy
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.policy.UserRestrictions

/** The platform surface [WifiRadioPolicy] needs, so its decisions are testable without Android. */
interface WifiRadio {
    fun isEnabled(): Boolean

    /** Switch the radio; false when the platform refused. */
    fun setEnabled(enabled: Boolean): Boolean

    /** True when a validated internet network that is NOT Wi-Fi exists (cellular, Ethernet, ...). */
    fun hasNonWifiInternet(): Boolean
}

/**
 * `wifiRadio`: switches the Wi-Fi radio itself (protocol 1.4). Deliberately separate from `wifi`, which has only
 * ever locked the Wi-Fi *settings* (`DISALLOW_CONFIG_WIFI`): configurations stored with `wifi = false` keep that
 * meaning, so upgrading never turns a device's Wi-Fi off. Polarity follows [TogglePolicy] (`true` = on).
 *
 *  - **Off**: `WifiManager.setWifiEnabled(false)`. Deprecated in API 29 and a no-op for ordinary apps targeting 29+,
 *    but Device Owners are exempt (WifiManager javadoc, "Deprecation Exemptions"). [PolicyOutcome.Deferred] while
 *    Wi-Fi is the device's only network (a VPN does not count), because the device would otherwise drop off the
 *    server for good; deferred does not block the rest of the configuration and is retried on the next re-apply.
 *    On API 33+ `DISALLOW_CHANGE_WIFI_STATE` then stops the user (and airplane mode) turning it back on; below 33 the
 *    user can re-enable it from quick settings until the next re-apply.
 *  - **On**: lifts that restriction and turns the radio on (the user may change it afterwards).
 *  - **Not managed** after Off ([release]): lifts the restriction and leaves the radio as it is.
 *
 * [restrict] is `(restrictionKey, add)`; [sdkInt] is passed in so the logic runs as a plain JVM test.
 */
class WifiRadioPolicy(
    private val radio: WifiRadio,
    private val restrict: (String, Boolean) -> Unit,
    private val sdkInt: Int,
    private val isDeviceOwner: () -> Boolean,
) : TogglePolicy, ReleasablePolicy {

    override val capabilityKey: String = CAPABILITY_KEY

    override fun isSupported(): Boolean = isDeviceOwner()

    override fun setEnabled(enabled: Boolean): PolicyOutcome = runCatching {
        if (enabled) turnOn() else turnOff()
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "wifi radio failed") }

    override fun release(): PolicyOutcome = runCatching {
        if (sdkInt >= TOGGLE_LOCK_SDK) restrict(UserRestrictions.DISALLOW_CHANGE_WIFI_STATE, false)
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "wifi radio release failed") }

    private fun turnOn(): PolicyOutcome {
        if (sdkInt >= TOGGLE_LOCK_SDK) restrict(UserRestrictions.DISALLOW_CHANGE_WIFI_STATE, false)
        val on = radio.isEnabled() || radio.setEnabled(true)
        return if (on) PolicyOutcome.Applied else PolicyOutcome.Failed("platform refused to turn Wi-Fi on")
    }

    private fun turnOff(): PolicyOutcome {
        val refusal = when {
            !radio.isEnabled() -> null
            !radio.hasNonWifiInternet() -> PolicyOutcome.Deferred(ONLY_NETWORK)
            !radio.setEnabled(false) -> PolicyOutcome.Failed("platform refused to turn Wi-Fi off")
            else -> null
        }
        if (refusal != null) return refusal
        if (sdkInt >= TOGGLE_LOCK_SDK) restrict(UserRestrictions.DISALLOW_CHANGE_WIFI_STATE, true)
        return PolicyOutcome.Applied
    }

    companion object {
        const val CAPABILITY_KEY = "wifiRadio"

        /** `UserManager.DISALLOW_CHANGE_WIFI_STATE` was added in API 33. */
        const val TOGGLE_LOCK_SDK = 33

        private const val ONLY_NETWORK =
            "Wi-Fi left on: it is the device's only network, turning it off would disconnect it"

        /** A network that keeps the device reachable without Wi-Fi: validated internet, not Wi-Fi, not a VPN. */
        fun isOtherInternet(internet: Boolean, validated: Boolean, wifi: Boolean, vpn: Boolean): Boolean =
            internet && validated && !wifi && !vpn
    }
}

/** [WifiRadio] over [WifiManager] and [ConnectivityManager]. */
class AndroidWifiRadio(context: Context) : WifiRadio {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun isEnabled(): Boolean = wifi.isWifiEnabled

    @Suppress("DEPRECATION") // Device Owners are exempt from the API 29 deprecation (see WifiRadioPolicy).
    override fun setEnabled(enabled: Boolean): Boolean = wifi.setWifiEnabled(enabled)

    @Suppress("DEPRECATION") // allNetworks: the only synchronous way to list every network.
    @SuppressLint("MissingPermission") // ACCESS_NETWORK_STATE is declared in the app manifest
    override fun hasNonWifiInternet(): Boolean = connectivity.allNetworks.any { n ->
        val caps = connectivity.getNetworkCapabilities(n) ?: return@any false
        WifiRadioPolicy.isOtherInternet(
            internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
        )
    }
}

/** Selects [WifiRadioPolicy] when the agent is Device Owner; null otherwise (never advertised). */
object WifiRadioPolicyFactory {
    fun create(handle: DpmHandle, radio: WifiRadio?): WifiRadioPolicy? {
        radio ?: return null
        return WifiRadioPolicy(
            radio = radio,
            restrict = { key, add ->
                if (add) {
                    handle.dpm.addUserRestriction(handle.admin, key)
                } else {
                    handle.dpm.clearUserRestriction(handle.admin, key)
                }
            },
            sdkInt = Build.VERSION.SDK_INT,
            isDeviceOwner = { handle.dpm.isDeviceOwnerApp(handle.admin.packageName) },
        ).takeIf { it.isSupported() }
    }
}
