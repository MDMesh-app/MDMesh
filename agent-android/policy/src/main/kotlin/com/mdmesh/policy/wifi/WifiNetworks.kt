package com.mdmesh.policy.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/** Security of a network saved through [WifiNetworks]; [wire] is the local API's `security` value. */
enum class WifiSecurity(val wire: String) {
    OPEN("open"),
    WEP("wep"),
    PSK("psk"),
    SAE("sae"),
    ;

    /** Whether [password] can be a key for this security at all. The platform still has the last word. */
    fun accepts(password: String): Boolean = when (this) {
        OPEN -> true
        PSK -> password.length in PSK_MIN..PSK_MAX || WifiKeys.isHex(password, PSK_HEX)
        WEP, SAE -> password.isNotEmpty()
    }

    companion object {
        /** WPA passphrase length bounds; a 64-digit hex string is the raw key instead. */
        const val PSK_MIN = 8
        const val PSK_MAX = 63
        const val PSK_HEX = 64

        fun fromWire(value: String?): WifiSecurity? = entries.firstOrNull { it.wire == value }
    }
}

/** A saved network: its platform id and its SSID, unquoted. */
data class SavedWifiNetwork(val networkId: Int, val ssid: String)

/**
 * The saved-network surface of [WifiManager], so the local API's decisions are testable without Android. The radio
 * itself is [WifiRadio].
 *
 * Every call here is deprecated for ordinary apps since API 29 and does nothing for them; Device Owners are exempt
 * (WifiManager javadoc, "Deprecation Exemptions"). That is why the pinned kiosk app asks the agent to do it.
 */
interface WifiNetworks {
    /**
     * Every saved network. Empty when location services are off: the platform hides the list from everyone but
     * system apps then, Device Owners included.
     */
    fun saved(): List<SavedWifiNetwork>

    /** Whether [security] can be saved on this device (SAE needs API 29). */
    fun supports(security: WifiSecurity): Boolean

    /** Save a network; its id, or [INVALID_ID] when the platform refused. */
    fun add(ssid: String, password: String, security: WifiSecurity): Int

    fun remove(networkId: Int): Boolean

    /** Allow auto-join and connect now (`enableNetwork(id, true)`, which switches networks by itself). */
    fun enable(networkId: Int): Boolean

    /** Stop auto-joining [networkId], keeping its credentials. */
    fun disable(networkId: Int): Boolean

    /** The network Wi-Fi is on now, or [INVALID_ID]. */
    fun connectedNetworkId(): Int

    fun disconnect(): Boolean

    fun reconnect(): Boolean

    companion object {
        /** `WifiConfiguration.INVALID_NETWORK_ID`. */
        const val INVALID_ID = -1
    }
}

/** [WifiNetworks] over [WifiManager]; create it with [from]. */
@Suppress("DEPRECATION") // Device Owners are exempt from the API 29 deprecation of all of these (see WifiNetworks).
class AndroidWifiNetworks private constructor(private val wifi: WifiManager) : WifiNetworks {

    companion object {
        /** Null on a device without Wi-Fi hardware (no [WifiManager]). */
        fun from(context: Context): AndroidWifiNetworks? =
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.let(::AndroidWifiNetworks)
    }

    @SuppressLint("MissingPermission") // ACCESS_WIFI_STATE and ACCESS_FINE_LOCATION are declared in the app manifest
    override fun saved(): List<SavedWifiNetwork> =
        wifi.configuredNetworks.orEmpty().map { SavedWifiNetwork(it.networkId, WifiKeys.unquote(it.SSID)) }

    override fun supports(security: WifiSecurity): Boolean =
        security != WifiSecurity.SAE || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun add(ssid: String, password: String, security: WifiSecurity): Int =
        wifi.addNetwork(WifiKeys.configuration(ssid, password, security))

    override fun remove(networkId: Int): Boolean = wifi.removeNetwork(networkId)

    override fun enable(networkId: Int): Boolean = wifi.enableNetwork(networkId, true)

    override fun disable(networkId: Int): Boolean = wifi.disableNetwork(networkId)

    @SuppressLint("MissingPermission") // ACCESS_WIFI_STATE is declared in the app manifest
    override fun connectedNetworkId(): Int = wifi.connectionInfo?.networkId ?: WifiNetworks.INVALID_ID

    override fun disconnect(): Boolean = wifi.disconnect()

    override fun reconnect(): Boolean = wifi.reconnect()
}
