package com.mdmesh.core.localapi

import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.policy.wifi.WifiNetworks
import com.mdmesh.policy.wifi.WifiRadio
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The agent's local, offline API: what the pinned kiosk app may ask the Device Owner to do for it, with no server
 * involved. Today that is Wi-Fi. Since Android 10 an ordinary app cannot add, enable, disable or remove saved networks
 * or switch the radio, and a kiosk tablet needs exactly that when it has no network at all.
 *
 * Android-free: the content provider in `:app` copies the call's Bundle into [LocalApiArgs] and [LocalApiReply.fields]
 * back into a Bundle, so every decision here runs as a plain JVM test.
 *
 * **Authorization**, on every call but [DESCRIBE] (which reports it), all of it local so it holds offline:
 *  1. the agent is Device Owner;
 *  2. the persisted kiosk is single-app (`mode == "single"`) and pins the caller;
 *  3. the caller is the package the platform verified against the calling UID, never an argument;
 *  4. the agent installed the caller (its installer of record is this agent).
 *
 * Calls run one at a time, so two `wifi.connect`s never interleave their remove and add.
 */
class LocalApi(
    private val platform: LocalApiPlatform,
    radio: WifiRadio?,
    networks: WifiNetworks?,
    private val events: EventSink,
) {
    /** Null on a device without Wi-Fi: every `wifi.*` method is then [LocalApiCode.UNSUPPORTED]. */
    private val wifi: WifiBridge? = if (radio != null && networks != null) WifiBridge(radio, networks) else null
    private val mutex = Mutex()

    /** Why a caller may not use the `wifi.*` methods; [reason] is the reply's `reason`. */
    enum class Denial(val reason: String, val message: String) {
        NOT_DEVICE_OWNER("not_device_owner", "the agent is not Device Owner"),
        NO_KIOSK("no_kiosk", "no single-app kiosk is active"),
        NOT_PINNED_APP("not_pinned_app", "the caller is not the pinned kiosk app"),
        NOT_INSTALLED_BY_AGENT("not_installed_by_agent", "the caller was not installed by the agent"),
    }

    /**
     * Answer [method] for [callingPackage] (as verified by the platform; null when unknown). Never throws: a failure
     * is an [LocalApiCode.ERROR] reply naming the exception class, never its message, which could carry the SSID.
     */
    suspend fun call(method: String, callingPackage: String?, args: LocalApiArgs): LocalApiReply {
        val reply = runCatching { mutex.withLock { route(method, callingPackage, args) } }
            .getOrElse { LocalApiReply.error("${logName(method)} failed: ${it.javaClass.simpleName}") }
        if (method in AUDITED) runCatching { events.record(EVENT_TYPE, "$method ${reply.code}") }
        return reply
    }

    private suspend fun route(method: String, caller: String?, args: LocalApiArgs): LocalApiReply = when (method) {
        DESCRIBE -> describe(denial(caller))
        !in METHODS -> LocalApiReply.unsupported("unknown method")
        else -> denial(caller)?.let(::unauthorized) ?: wifiCall(method, args)
    }

    private fun wifiCall(method: String, args: LocalApiArgs): LocalApiReply {
        val w = wifi ?: return LocalApiReply.unsupported("this device has no Wi-Fi")
        return when (method) {
            WIFI_CONNECT -> w.connect(args)
            WIFI_SAVED -> w.saved()
            WIFI_FORGET -> w.forget(args)
            WIFI_SET_AUTO_JOIN -> w.setAutoJoin(args)
            WIFI_DISCONNECT -> w.disconnect()
            WIFI_SET_RADIO -> w.setRadio(args)
            else -> LocalApiReply.unsupported("unknown method")
        }
    }

    /** Why [caller] may not use the `wifi.*` methods, or null when it may. */
    private suspend fun denial(caller: String?): Denial? {
        val kiosk = platform.kiosk()
        return when {
            !platform.isDeviceOwner() -> Denial.NOT_DEVICE_OWNER
            kiosk == null || kiosk.mode != KIOSK_SINGLE -> Denial.NO_KIOSK
            caller == null || caller != kiosk.pinPackage -> Denial.NOT_PINNED_APP
            platform.installerOf(caller) != platform.ownPackage -> Denial.NOT_INSTALLED_BY_AGENT
            else -> null
        }
    }

    private fun describe(denial: Denial?): LocalApiReply {
        val values = mapOf(
            LocalApiKeys.METHODS to METHODS,
            LocalApiKeys.AUTHORIZED to (denial == null),
        ) + reasonOf(denial)
        return LocalApiReply.ok(denial?.message ?: "authorized", values)
    }

    private fun unauthorized(denial: Denial) =
        LocalApiReply(LocalApiCode.UNAUTHORIZED, denial.message, reasonOf(denial))

    private fun reasonOf(denial: Denial?): Map<String, Any> =
        denial?.let { mapOf(LocalApiKeys.REASON to it.reason) }.orEmpty()

    companion object {
        /** Bumped only for a change an existing caller would notice; added methods or keys keep it. */
        const val API_VERSION = 1

        const val DESCRIBE = "describe"
        const val WIFI_CONNECT = "wifi.connect"
        const val WIFI_SAVED = "wifi.saved"
        const val WIFI_FORGET = "wifi.forget"
        const val WIFI_SET_AUTO_JOIN = "wifi.setAutoJoin"
        const val WIFI_DISCONNECT = "wifi.disconnect"
        const val WIFI_SET_RADIO = "wifi.setRadio"

        /** Every method, as `describe` lists them. */
        val METHODS: List<String> = listOf(
            DESCRIBE, WIFI_CONNECT, WIFI_SAVED, WIFI_FORGET, WIFI_SET_AUTO_JOIN, WIFI_DISCONNECT, WIFI_SET_RADIO,
        )

        /** The methods that change something, recorded in the event log as `localApi` `<method> <code>`. */
        val AUDITED: Set<String> = setOf(WIFI_CONNECT, WIFI_FORGET, WIFI_SET_AUTO_JOIN, WIFI_DISCONNECT, WIFI_SET_RADIO)

        const val EVENT_TYPE = "localApi"

        /** [com.mdmesh.proto.KioskApplyPayload.mode] of a single-app kiosk. */
        private const val KIOSK_SINGLE = "single"

        /** [method] when it is one of ours, else a placeholder: the method name comes from the caller. */
        fun logName(method: String): String = if (method in METHODS) method else "unknown"
    }
}
