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
 * What [WifiRadioPolicy] must remember across reboots. Written synchronously (before the radio is switched), so a
 * process death in between cannot lose it.
 */
interface WifiRadioOffState {
    /** When the policy turned Wi-Fi off (wall clock); null = Wi-Fi is not off because of the policy. */
    var offSinceMs: Long?

    /** [WifiRadioPolicy.selfHeal] turned Wi-Fi back on: Off is deferred until the configuration changes. */
    var heldOn: Boolean
}

/** Not persisted: for tests and devices without a store. */
class InMemoryWifiRadioOffState : WifiRadioOffState {
    override var offSinceMs: Long? = null
    override var heldOn: Boolean = false
}

/** [WifiRadioOffState] in SharedPreferences (commit, not apply: it must be on disk before the radio changes). */
class SharedPrefsWifiRadioOffState(context: Context) : WifiRadioOffState {
    private val prefs = context.applicationContext.getSharedPreferences("mdm_wifi_radio", Context.MODE_PRIVATE)
    override var offSinceMs: Long?
        get() = prefs.getLong(KEY_SINCE, -1L).takeIf { it >= 0 }
        set(v) { prefs.edit().putLong(KEY_SINCE, v ?: -1L).commit() }
    override var heldOn: Boolean
        get() = prefs.getBoolean(KEY_HELD, false)
        set(v) { prefs.edit().putBoolean(KEY_HELD, v).commit() }
    private companion object {
        const val KEY_SINCE = "offSince"
        const val KEY_HELD = "heldOn"
    }
}

/**
 * `wifiRadio`: switches the Wi-Fi radio itself (protocol 1.4). Deliberately separate from `wifi`, which has only
 * ever locked the Wi-Fi *settings* (`DISALLOW_CONFIG_WIFI`): configurations stored with `wifi = false` keep that
 * meaning, so upgrading never turns a device's Wi-Fi off. Polarity follows [TogglePolicy] (`true` = on).
 *
 *  - **Off**: `WifiManager.setWifiEnabled(false)`. Deprecated in API 29 and a no-op for ordinary apps targeting 29+,
 *    but Device Owners are exempt (WifiManager javadoc, "Deprecation Exemptions"). [PolicyOutcome.Deferred] while
 *    Wi-Fi is the device's only network (a VPN does not count); deferred does not block the rest of the
 *    configuration. On API 33+ `DISALLOW_CHANGE_WIFI_STATE` then stops the user (and airplane mode) turning it back
 *    on; below 33 the user can re-enable it from quick settings until the next re-apply.
 *  - **On**: lifts that restriction and turns the radio on (the user may change it afterwards).
 *  - **Not managed** after Off ([release]): lifts the restriction and leaves the radio as it is.
 *  - **Self-heal** ([selfHeal], from the agent's periodic heartbeat): when Wi-Fi is off because of the policy and the
 *    device has not reached the server for [HEAL_AFTER_MS] since it was turned off (the SIM was removed, or the
 *    server is only reachable over the Wi-Fi network), Wi-Fi is turned back on and the lock lifted. It then stays on
 *    ([WifiRadioOffState.heldOn]: Off is deferred) until the configuration changes ([endHold]) or On / Not managed.
 *
 * [restrict] is `(restrictionKey, add)`; [sdkInt] and [clock] are passed in so the logic runs as a plain JVM test.
 */
class WifiRadioPolicy(
    private val radio: WifiRadio,
    private val restrict: (String, Boolean) -> Unit,
    private val sdkInt: Int,
    private val state: WifiRadioOffState = InMemoryWifiRadioOffState(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val isDeviceOwner: () -> Boolean,
) : TogglePolicy, ReleasablePolicy {

    override val capabilityKey: String = CAPABILITY_KEY

    override fun isSupported(): Boolean = isDeviceOwner()

    override fun setEnabled(enabled: Boolean): PolicyOutcome = runCatching {
        if (enabled) turnOn() else turnOff()
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "wifi radio failed") }

    override fun release(): PolicyOutcome = runCatching {
        unlock()
        state.heldOn = false
        // Still off because of the policy: keep the self-heal armed, the device may not reach the server.
        if (radio.isEnabled()) state.offSinceMs = null
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "wifi radio release failed") }

    /** The configuration changed: a self-heal no longer holds Wi-Fi on, the new Off is applied as usual. */
    fun endHold() {
        state.heldOn = false
    }

    /**
     * Heartbeat check (no loop of its own: the agent's periodic heartbeat calls it, about every 10 minutes). Turns
     * Wi-Fi back on when the policy turned it off and the device has not reached the server for [HEAL_AFTER_MS]
     * since then.
     *
     * @param lastCheckInOkMs when the last check-in succeeded (wall clock), null when never.
     * @return true when Wi-Fi was turned back on (the caller then has the configuration re-sent, so the console
     *   shows the Off as deferred). False when the platform refused: the next heartbeat retries.
     */
    fun selfHeal(lastCheckInOkMs: Long?): Boolean = runCatching { selfHealUnguarded(lastCheckInOkMs) }
        .getOrDefault(false)

    private fun selfHealUnguarded(lastCheckInOkMs: Long?): Boolean {
        val now = clock()
        val offSince = state.offSinceMs?.let { since ->
            when {
                // Wi-Fi is on again (the user below Android 13, or after Not managed): nothing to heal any more.
                radio.isEnabled() -> null.also { state.offSinceMs = null }
                // The clock went back: restart from now rather than wait for the old time to come round again.
                now < since -> now.also { state.offSinceMs = it }
                else -> since
            }
        }
        // A check-in "in the future" is from before the clock went back: ignore it.
        val reachedServer = lastCheckInOkMs?.takeIf { it <= now } ?: 0L
        val due = offSince != null && now - maxOf(offSince, reachedServer) >= HEAL_AFTER_MS
        if (!due) return false
        unlock()
        val on = radio.setEnabled(true) || radio.isEnabled()
        if (on) {
            state.heldOn = true
            // Not cleared: the next heartbeat that sees Wi-Fi on clears it; if the radio never came up, this retries.
            state.offSinceMs = now
        }
        return on
    }

    private fun unlock() {
        if (sdkInt >= TOGGLE_LOCK_SDK) restrict(UserRestrictions.DISALLOW_CHANGE_WIFI_STATE, false)
    }

    private fun turnOn(): PolicyOutcome {
        unlock()
        state.heldOn = false
        state.offSinceMs = null
        val on = radio.isEnabled() || radio.setEnabled(true)
        return if (on) PolicyOutcome.Applied else PolicyOutcome.Failed("platform refused to turn Wi-Fi on")
    }

    private fun turnOff(): PolicyOutcome {
        val refusal = when {
            state.heldOn -> PolicyOutcome.Deferred(HELD_ON)
            !radio.isEnabled() -> null
            !radio.hasNonWifiInternet() -> PolicyOutcome.Deferred(ONLY_NETWORK).also { state.offSinceMs = null }
            else -> switchOff()
        }
        if (refusal != null) return refusal
        // Already off: a boot re-apply keeps the time it was turned off.
        if (state.offSinceMs == null) state.offSinceMs = clock()
        if (sdkInt >= TOGGLE_LOCK_SDK) restrict(UserRestrictions.DISALLOW_CHANGE_WIFI_STATE, true)
        return PolicyOutcome.Applied
    }

    /** @return null when Wi-Fi is now off, else the failure. */
    private fun switchOff(): PolicyOutcome? {
        // Persisted first: a process death right after the switch must not lose the self-heal.
        state.offSinceMs = clock()
        if (radio.setEnabled(false)) return null
        state.offSinceMs = null
        return PolicyOutcome.Failed("platform refused to turn Wi-Fi off")
    }

    companion object {
        const val CAPABILITY_KEY = "wifiRadio"

        /** `UserManager.DISALLOW_CHANGE_WIFI_STATE` was added in API 33. */
        const val TOGGLE_LOCK_SDK = 33

        /**
         * How long Wi-Fi may stay off by policy without a check-in before [selfHeal] turns it back on: about three
         * heartbeats (10 minutes each, and they fire late on an idle device), so one late heartbeat never heals.
         */
        const val HEAL_AFTER_MS: Long = 30L * 60L * 1000L

        private const val ONLY_NETWORK = "no other network: Wi-Fi left on, it is the device's only connection"
        private const val HELD_ON = "no connection to the server without Wi-Fi: Wi-Fi turned back on"

        /** A network that keeps the device reachable without Wi-Fi: validated internet, not Wi-Fi, not a VPN. */
        fun isOtherInternet(internet: Boolean, validated: Boolean, wifi: Boolean, vpn: Boolean): Boolean =
            internet && validated && !wifi && !vpn
    }
}

/** [WifiRadio] over [WifiManager] and [ConnectivityManager]; create it with [from]. */
class AndroidWifiRadio private constructor(
    private val wifi: WifiManager,
    private val connectivity: ConnectivityManager,
) : WifiRadio {

    companion object {
        /** Null on a device without Wi-Fi hardware (no [WifiManager]): `wifiRadio` is then never advertised. */
        fun from(context: Context): AndroidWifiRadio? {
            val app = context.applicationContext
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            return if (wifi != null && connectivity != null) AndroidWifiRadio(wifi, connectivity) else null
        }
    }

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
    fun create(
        handle: DpmHandle,
        radio: WifiRadio?,
        state: WifiRadioOffState = InMemoryWifiRadioOffState(),
    ): WifiRadioPolicy? {
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
            state = state,
        ).takeIf { it.isSupported() }
    }
}
