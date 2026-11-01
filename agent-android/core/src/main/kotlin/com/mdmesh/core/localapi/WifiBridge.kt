package com.mdmesh.core.localapi

import com.mdmesh.policy.wifi.SavedWifiNetwork
import com.mdmesh.policy.wifi.WifiNetworks
import com.mdmesh.policy.wifi.WifiRadio
import com.mdmesh.policy.wifi.WifiSecurity

/**
 * The `wifi.*` methods of the [LocalApi], over [WifiRadio] and [WifiNetworks]. [LocalApi] has authorized the caller
 * before any of these runs. Replies never carry the password, and their messages never carry the SSID.
 */
internal class WifiBridge(
    private val radio: WifiRadio,
    private val networks: WifiNetworks,
) {

    /**
     * `wifi.connect`: replace every saved network with this SSID by one with these credentials, and join it.
     * `enableNetwork(id, true)` switches networks by itself, so there is no `disconnect()` first and nothing waits.
     */
    fun connect(args: LocalApiArgs): LocalApiReply {
        val ssid = args.string(LocalApiKeys.SSID)?.takeIf(::fitsSsid)
        val security = WifiSecurity.fromWire(args.string(LocalApiKeys.SECURITY))
        val password = args.string(LocalApiKeys.PASSWORD).orEmpty()
        return when {
            ssid == null -> LocalApiReply.badRequest("ssid is missing, empty or longer than $MAX_SSID_BYTES bytes")
            security == null -> LocalApiReply.badRequest("security must be one of $SECURITIES")
            !security.accepts(password) -> LocalApiReply.badRequest("the password does not fit ${security.wire}")
            !networks.supports(security) -> LocalApiReply.unsupported("${security.wire} needs a newer Android version")
            !radioOn() -> LocalApiReply.refused("the platform refused to turn Wi-Fi on")
            else -> save(ssid, password, security)
        }
    }

    /** Turn the radio on when it is off. The network is saved and enabled while it comes up: nothing waits for it. */
    private fun radioOn(): Boolean = radio.isEnabled() || radio.setEnabled(true)

    private fun save(ssid: String, password: String, security: WifiSecurity): LocalApiReply {
        // Best effort: a network that will not go only stays saved next to the new one, which is still joined.
        matching(ssid).forEach { networks.remove(it.networkId) }
        val id = networks.add(ssid, password, security)
        val joined = id != WifiNetworks.INVALID_ID && networks.enable(id)
        if (joined) networks.reconnect()
        val values = mapOf(LocalApiKeys.NETWORK_ID to id)
        return when {
            id == WifiNetworks.INVALID_ID -> LocalApiReply.refused("the platform refused to save the network")
            !joined -> LocalApiReply.refused("the network is saved, but the platform refused to join it", values)
            else -> LocalApiReply.ok("network saved, joining it", values)
        }
    }

    /** `wifi.saved`: the SSIDs of the saved networks, each once. */
    fun saved(): LocalApiReply {
        val ssids = networks.saved().map { it.ssid }.distinct()
        return LocalApiReply.ok("${ssids.size} saved network(s)", mapOf(LocalApiKeys.SSIDS to ssids))
    }

    /** `wifi.forget`: remove every saved network with this SSID. */
    fun forget(args: LocalApiArgs): LocalApiReply {
        val ssid = args.ssid()
        val found = ssid?.let(::matching).orEmpty()
        // Every one is tried, even after a refusal.
        val removed = found.map { networks.remove(it.networkId) }
        return when {
            ssid == null -> LocalApiReply.badRequest(BAD_SSID)
            found.isEmpty() -> LocalApiReply.notFound()
            removed.all { it } -> LocalApiReply.ok("network forgotten")
            else -> LocalApiReply.refused("the platform refused to remove the network")
        }
    }

    /**
     * `wifi.setAutoJoin`: enabled joins the network now and lets Wi-Fi join it again later; disabled stops that, and
     * leaves it if Wi-Fi is on it. The credentials stay saved either way.
     */
    fun setAutoJoin(args: LocalApiArgs): LocalApiReply {
        val ssid = args.ssid()
        val enabled = args.boolean(LocalApiKeys.ENABLED)
        val found = ssid?.let(::matching).orEmpty()
        return when {
            ssid == null -> LocalApiReply.badRequest(BAD_SSID)
            enabled == null -> LocalApiReply.badRequest(BAD_ENABLED)
            found.isEmpty() -> LocalApiReply.notFound()
            else -> applyAutoJoin(found.map { it.networkId }, enabled)
        }
    }

    private fun applyAutoJoin(ids: List<Int>, enabled: Boolean): LocalApiReply {
        val accepted = ids.map { if (enabled) networks.enable(it) else networks.disable(it) }
        if (!enabled && networks.connectedNetworkId() in ids) networks.disconnect()
        return if (accepted.all { it }) {
            LocalApiReply.ok(if (enabled) "auto-join on" else "auto-join off")
        } else {
            LocalApiReply.refused("the platform refused to change auto-join")
        }
    }

    /** `wifi.disconnect`: leave the current network. Wi-Fi may join a saved network again later. */
    fun disconnect(): LocalApiReply =
        if (networks.disconnect()) {
            LocalApiReply.ok("disconnected")
        } else {
            LocalApiReply.refused("the platform refused to disconnect")
        }

    /** `wifi.setRadio`: switch the Wi-Fi radio. */
    fun setRadio(args: LocalApiArgs): LocalApiReply {
        val enabled = args.boolean(LocalApiKeys.ENABLED)
        return when {
            enabled == null -> LocalApiReply.badRequest(BAD_ENABLED)
            radio.setEnabled(enabled) -> LocalApiReply.ok(if (enabled) "Wi-Fi turning on" else "Wi-Fi turning off")
            else -> LocalApiReply.refused("the platform refused to switch Wi-Fi")
        }
    }

    private fun matching(ssid: String): List<SavedWifiNetwork> = networks.saved().filter { it.ssid == ssid }

    private companion object {
        /** An SSID is at most 32 bytes (IEEE 802.11). */
        const val MAX_SSID_BYTES = 32
        const val BAD_SSID = "ssid is missing or empty"
        const val BAD_ENABLED = "enabled is missing or not a boolean"
        val SECURITIES = WifiSecurity.entries.joinToString { it.wire }

        fun fitsSsid(ssid: String): Boolean =
            ssid.isNotEmpty() && ssid.toByteArray(Charsets.UTF_8).size <= MAX_SSID_BYTES

        /** The SSID of a saved network to act on. Not length-checked: a non-UTF-8 one is listed in hex. */
        fun LocalApiArgs.ssid(): String? = string(LocalApiKeys.SSID)?.takeIf { it.isNotEmpty() }
    }
}
