package com.mdmesh.core.localapi

import com.mdmesh.core.localapi.LocalApi.Companion.DESCRIBE
import com.mdmesh.core.localapi.LocalApi.Companion.DEVICE_REBOOT
import com.mdmesh.core.localapi.LocalApi.Companion.WIFI_CONNECT
import com.mdmesh.core.localapi.LocalApi.Companion.WIFI_DISCONNECT
import com.mdmesh.core.localapi.LocalApi.Companion.WIFI_FORGET
import com.mdmesh.core.localapi.LocalApi.Companion.WIFI_SAVED
import com.mdmesh.core.localapi.LocalApi.Companion.WIFI_SET_AUTO_JOIN
import com.mdmesh.core.localapi.LocalApi.Companion.WIFI_SET_RADIO
import com.mdmesh.core.localapi.LocalApiKeys.ENABLED
import com.mdmesh.core.localapi.LocalApiKeys.PASSWORD
import com.mdmesh.core.localapi.LocalApiKeys.SECURITY
import com.mdmesh.core.localapi.LocalApiKeys.SSID
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.policy.wifi.SavedWifiNetwork
import com.mdmesh.policy.wifi.WifiNetworks
import com.mdmesh.policy.wifi.WifiRadio
import com.mdmesh.policy.wifi.WifiSecurity
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.TelemetryEventDto
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local API over a fake Wi-Fi stack and fake device facts (no Android types): who may call it, what each method
 * asks of WifiManager, and that no reply ever carries the password.
 */
class LocalApiTest {

    private class FakeWifi : WifiRadio, WifiNetworks {
        var radioOn = true
        var radioAccepts = true
        var addAccepts = true
        var enableAccepts = true
        var removeAccepts = true
        var disconnectAccepts = true
        var saeSupported = true
        var connectedId = WifiNetworks.INVALID_ID
        var addThrows: Throwable? = null
        var nextId = 10
        val networks = mutableListOf<SavedWifiNetwork>()
        val calls = mutableListOf<String>()
        val added = mutableListOf<Pair<String, WifiSecurity>>()
        var addedPassword: String? = null

        override fun isEnabled() = radioOn
        override fun setEnabled(enabled: Boolean): Boolean {
            calls += "radio:$enabled"
            if (radioAccepts) radioOn = enabled
            return radioAccepts
        }
        override fun hasNonWifiInternet() = false

        override fun saved(): List<SavedWifiNetwork> = networks.toList()
        override fun supports(security: WifiSecurity) = security != WifiSecurity.SAE || saeSupported
        override fun add(ssid: String, password: String, security: WifiSecurity): Int {
            calls += "add"
            addThrows?.let { throw it }
            added += ssid to security
            addedPassword = password
            val id = if (addAccepts) nextId++ else WifiNetworks.INVALID_ID
            if (addAccepts) networks += SavedWifiNetwork(id, ssid)
            return id
        }
        override fun remove(networkId: Int): Boolean {
            calls += "remove:$networkId"
            // Like WifiManager.removeNetwork: false for an id that is not (or no longer) saved.
            val present = networks.any { it.networkId == networkId }
            if (removeAccepts) networks.removeAll { it.networkId == networkId }
            return removeAccepts && present
        }
        override fun enable(networkId: Int): Boolean = enableAccepts.also { calls += "enable:$networkId" }
        override fun disable(networkId: Int): Boolean = true.also { calls += "disable:$networkId" }
        override fun connectedNetworkId() = connectedId
        override fun disconnect(): Boolean = disconnectAccepts.also { calls += "disconnect" }
        override fun reconnect(): Boolean = true.also { calls += "reconnect" }
    }

    private class FakePlatform(
        var deviceOwner: Boolean = true,
        var kiosk: KioskApplyPayload? = KioskApplyPayload(mode = "single", pinPackage = APP),
        var installer: String? = AGENT,
    ) : LocalApiPlatform {
        /** Asked from the reboot's own thread, so counted atomically. */
        val reboots = AtomicInteger()
        var rebootThrows: Throwable? = null

        /** Set, the reboot blocks until it opens, as Android's does when it goes ahead. */
        var rebootBlocksUntil: CountDownLatch? = null

        @Volatile var rebootReturned = false

        override val ownPackage = AGENT
        override fun isDeviceOwner() = deviceOwner
        override suspend fun kiosk() = kiosk
        override fun installerOf(packageName: String) = installer
        override fun reboot() {
            reboots.incrementAndGet()
            rebootThrows?.let { throw it }
            rebootBlocksUntil?.await(10, TimeUnit.SECONDS)
            rebootReturned = true
        }
    }

    private class RecordingEvents : EventSink {
        val recorded = mutableListOf<Pair<String, String?>>()
        override fun record(type: String, detail: String?) {
            recorded += type to detail
        }
        override fun drain(max: Int): List<TelemetryEventDto> = emptyList()
        override fun restore(events: List<TelemetryEventDto>) = Unit
    }

    private val wifi = FakeWifi()
    private val platform = FakePlatform()
    private val events = RecordingEvents()
    private val api = LocalApi(platform, wifi, wifi, events)

    private fun call(method: String, vararg args: Pair<String, Any?>, caller: String? = APP): LocalApiReply =
        runBlocking { api.call(method, caller, LocalApiArgs(mapOf(*args))) }

    private fun connect(ssid: Any? = HOME, password: Any? = SECRET, security: Any? = "psk") =
        call(WIFI_CONNECT, SSID to ssid, PASSWORD to password, SECURITY to security)

    private fun assertCode(code: String, reply: LocalApiReply) = assertEquals(reply.message, code, reply.code)

    // --- describe ---

    @Test fun `describe lists the methods and says the pinned app is authorized`() {
        val r = call(DESCRIBE)
        assertCode(LocalApiCode.OK, r)
        val f = r.fields()
        assertEquals(true, f[LocalApiKeys.OK])
        assertEquals(1, f[LocalApiKeys.API_VERSION])
        assertEquals(true, f[LocalApiKeys.AUTHORIZED])
        assertNull(f[LocalApiKeys.REASON])
        val all = listOf(
            DESCRIBE, WIFI_CONNECT, WIFI_SAVED, WIFI_FORGET, WIFI_SET_AUTO_JOIN, WIFI_DISCONNECT, WIFI_SET_RADIO,
            DEVICE_REBOOT,
        )
        assertEquals(all, f[LocalApiKeys.METHODS])
    }

    @Test fun `describe answers an unauthorized caller too, with the reason`() {
        platform.deviceOwner = false
        val r = call(DESCRIBE, caller = "com.android.shell")
        assertCode(LocalApiCode.OK, r)
        assertEquals(false, r.fields()[LocalApiKeys.AUTHORIZED])
        assertEquals("not_device_owner", r.fields()[LocalApiKeys.REASON])
        assertTrue(wifi.calls.isEmpty())
    }

    @Test fun `an unknown method is unsupported and carries the api version, authorized or not`() {
        val r = call("wifi.scan")
        assertCode(LocalApiCode.UNSUPPORTED, r)
        assertFalse(r.ok)
        assertEquals(1, r.fields()[LocalApiKeys.API_VERSION])
        platform.kiosk = null
        assertCode(LocalApiCode.UNSUPPORTED, call("wifi.scan"))
    }

    // --- authorization ---

    private fun assertDenied(reason: String, caller: String? = APP) {
        val r = call(WIFI_SAVED, caller = caller)
        assertCode(LocalApiCode.UNAUTHORIZED, r)
        assertEquals(reason, r.fields()[LocalApiKeys.REASON])
        assertEquals(false, r.fields()[LocalApiKeys.OK])
        val connect = call(WIFI_CONNECT, SSID to HOME, PASSWORD to SECRET, SECURITY to "psk", caller = caller)
        assertCode(LocalApiCode.UNAUTHORIZED, connect)
        assertTrue("an unauthorized call must not reach WifiManager: ${wifi.calls}", wifi.calls.isEmpty())
        val reboot = call(DEVICE_REBOOT, caller = caller)
        assertCode(LocalApiCode.UNAUTHORIZED, reboot)
        assertEquals(reason, reboot.fields()[LocalApiKeys.REASON])
        assertEquals("an unauthorized caller must not reboot the device", 0, platform.reboots.get())
    }

    @Test fun `the pinned app installed by the agent is authorized`() {
        wifi.networks += SavedWifiNetwork(1, HOME)
        val r = call(WIFI_SAVED)
        assertCode(LocalApiCode.OK, r)
        assertEquals(listOf(HOME), r.fields()[LocalApiKeys.SSIDS])
    }

    @Test fun `not Device Owner is refused`() {
        platform.deviceOwner = false
        assertDenied("not_device_owner")
    }

    @Test fun `no kiosk is refused`() {
        platform.kiosk = null
        assertDenied("no_kiosk")
    }

    @Test fun `a launcher-mode kiosk is refused, even for an allowed app`() {
        platform.kiosk = KioskApplyPayload(mode = "launcher", allowedPackages = listOf(APP), pinPackage = APP)
        assertDenied("no_kiosk")
    }

    @Test fun `any caller but the pinned app is refused`() {
        assertDenied("not_pinned_app", caller = "com.android.shell")
        assertDenied("not_pinned_app", caller = null)
        platform.kiosk = KioskApplyPayload(mode = "single", pinPackage = null)
        assertDenied("not_pinned_app")
    }

    @Test fun `a pinned app the agent did not install is refused`() {
        platform.installer = "com.android.vending"
        assertDenied("not_installed_by_agent")
        platform.installer = null
        assertDenied("not_installed_by_agent")
    }

    // --- wifi.connect ---

    @Test fun `connect replaces every saved config for the SSID, then joins the new one`() {
        wifi.networks += listOf(SavedWifiNetwork(1, HOME), SavedWifiNetwork(2, "Other"), SavedWifiNetwork(3, HOME))
        val r = connect()
        assertCode(LocalApiCode.OK, r)
        assertEquals(10, r.fields()[LocalApiKeys.NETWORK_ID])
        assertEquals(listOf("remove:1", "remove:3", "add", "enable:10", "reconnect"), wifi.calls)
        assertEquals(listOf(HOME to WifiSecurity.PSK), wifi.added)
        assertEquals(SECRET, wifi.addedPassword)
        assertEquals(listOf(2, 10), wifi.networks.map { it.networkId })
    }

    @Test fun `connect turns the radio on first when it is off`() {
        wifi.radioOn = false
        assertCode(LocalApiCode.OK, connect())
        assertEquals("radio:true", wifi.calls.first())
        assertFalse("never a disconnect before joining", "disconnect" in wifi.calls)
    }

    @Test fun `connect is refused when the radio will not come on`() {
        wifi.radioOn = false
        wifi.radioAccepts = false
        assertCode(LocalApiCode.REFUSED, connect())
        assertFalse("add" in wifi.calls)
    }

    @Test fun `connect takes every security type`() {
        assertCode(LocalApiCode.OK, connect(security = "open", password = ""))
        assertCode(LocalApiCode.OK, connect(security = "open", password = null))
        assertCode(LocalApiCode.OK, connect(security = "wep", password = "abcde"))
        assertCode(LocalApiCode.OK, connect(security = "sae", password = SECRET))
        assertEquals(
            listOf(WifiSecurity.OPEN, WifiSecurity.OPEN, WifiSecurity.WEP, WifiSecurity.SAE),
            wifi.added.map { it.second },
        )
    }

    @Test fun `SAE is unsupported where the platform has no SAE`() {
        wifi.saeSupported = false
        assertCode(LocalApiCode.UNSUPPORTED, connect(security = "sae"))
        assertTrue(wifi.calls.isEmpty())
    }

    @Test fun `connect rejects missing or invalid extras before touching Wi-Fi`() {
        listOf(
            connect(ssid = null),
            connect(ssid = ""),
            connect(ssid = 42),
            connect(ssid = "x".repeat(33)),
            connect(ssid = "é".repeat(17)), // 34 bytes in UTF-8
            connect(security = null),
            connect(security = "wpa3"),
            connect(password = null),
            connect(password = "short"),
            connect(security = "wep", password = ""),
            connect(security = "sae", password = ""),
        ).forEach { assertCode(LocalApiCode.BAD_REQUEST, it) }
        assertTrue(wifi.calls.isEmpty())
        assertCode(LocalApiCode.OK, connect(ssid = "x".repeat(32)))
    }

    @Test fun `connect reports a platform refusal to save or to join`() {
        wifi.addAccepts = false
        assertCode(LocalApiCode.REFUSED, connect())
        assertFalse(wifi.calls.any { it.startsWith("enable") })

        wifi.addAccepts = true
        wifi.enableAccepts = false
        val r = connect()
        assertCode(LocalApiCode.REFUSED, r)
        assertEquals(10, r.fields()[LocalApiKeys.NETWORK_ID])
        assertFalse("reconnect" in wifi.calls)
    }

    // --- wifi.saved ---

    @Test fun `saved lists each SSID once`() {
        wifi.networks += listOf(SavedWifiNetwork(1, HOME), SavedWifiNetwork(2, "Office"), SavedWifiNetwork(3, HOME))
        assertEquals(listOf(HOME, "Office"), call(WIFI_SAVED).fields()[LocalApiKeys.SSIDS])
        wifi.networks.clear()
        assertEquals(emptyList<String>(), call(WIFI_SAVED).fields()[LocalApiKeys.SSIDS])
    }

    // --- wifi.forget ---

    @Test fun `forget removes every config with the SSID`() {
        wifi.networks += listOf(SavedWifiNetwork(1, HOME), SavedWifiNetwork(2, "Office"), SavedWifiNetwork(3, HOME))
        assertCode(LocalApiCode.OK, call(WIFI_FORGET, SSID to HOME))
        assertEquals(listOf("remove:1", "remove:3"), wifi.calls)
        assertEquals(listOf("Office"), wifi.networks.map { it.ssid })
    }

    @Test fun `forget removes a WPA2-WPA3 network once, though Android lists it once per security type`() {
        wifi.networks += listOf(SavedWifiNetwork(5, HOME), SavedWifiNetwork(5, HOME))
        assertCode(LocalApiCode.OK, call(WIFI_FORGET, SSID to HOME))
        assertEquals(listOf("remove:5"), wifi.calls)
        assertEquals(emptyList<SavedWifiNetwork>(), wifi.networks)
    }

    @Test fun `forget reports not found, a bad request, and a refusal`() {
        assertCode(LocalApiCode.NOT_FOUND, call(WIFI_FORGET, SSID to HOME))
        assertCode(LocalApiCode.BAD_REQUEST, call(WIFI_FORGET))
        assertCode(LocalApiCode.BAD_REQUEST, call(WIFI_FORGET, SSID to ""))
        wifi.networks += listOf(SavedWifiNetwork(1, HOME), SavedWifiNetwork(3, HOME))
        wifi.removeAccepts = false
        assertCode(LocalApiCode.REFUSED, call(WIFI_FORGET, SSID to HOME))
        assertEquals("every one is still tried", listOf("remove:1", "remove:3"), wifi.calls)
    }

    // --- wifi.setAutoJoin ---

    @Test fun `auto-join on enables the network`() {
        wifi.networks += SavedWifiNetwork(4, HOME)
        assertCode(LocalApiCode.OK, call(WIFI_SET_AUTO_JOIN, SSID to HOME, ENABLED to true))
        assertEquals(listOf("enable:4"), wifi.calls)
    }

    @Test fun `auto-join off disables the network and leaves it when Wi-Fi is on it`() {
        wifi.networks += listOf(SavedWifiNetwork(4, HOME), SavedWifiNetwork(5, "Office"))
        wifi.connectedId = 5
        assertCode(LocalApiCode.OK, call(WIFI_SET_AUTO_JOIN, SSID to HOME, ENABLED to false))
        assertEquals("not on it: no disconnect", listOf("disable:4"), wifi.calls)

        wifi.calls.clear()
        wifi.connectedId = 4
        assertCode(LocalApiCode.OK, call(WIFI_SET_AUTO_JOIN, SSID to HOME, ENABLED to false))
        assertEquals(listOf("disable:4", "disconnect"), wifi.calls)
        assertEquals("the credentials stay", 2, wifi.networks.size)
    }

    @Test fun `auto-join reports not found and bad requests`() {
        assertCode(LocalApiCode.NOT_FOUND, call(WIFI_SET_AUTO_JOIN, SSID to HOME, ENABLED to true))
        wifi.networks += SavedWifiNetwork(4, HOME)
        assertCode(LocalApiCode.BAD_REQUEST, call(WIFI_SET_AUTO_JOIN, SSID to HOME))
        assertCode(LocalApiCode.BAD_REQUEST, call(WIFI_SET_AUTO_JOIN, SSID to HOME, ENABLED to "true"))
        assertCode(LocalApiCode.BAD_REQUEST, call(WIFI_SET_AUTO_JOIN, ENABLED to true))
        wifi.enableAccepts = false
        assertCode(LocalApiCode.REFUSED, call(WIFI_SET_AUTO_JOIN, SSID to HOME, ENABLED to true))
    }

    // --- wifi.disconnect / wifi.setRadio ---

    @Test fun `disconnect leaves the network, or reports the refusal`() {
        assertCode(LocalApiCode.OK, call(WIFI_DISCONNECT))
        assertEquals(listOf("disconnect"), wifi.calls)
        wifi.disconnectAccepts = false
        assertCode(LocalApiCode.REFUSED, call(WIFI_DISCONNECT))
    }

    @Test fun `setRadio switches the radio`() {
        assertCode(LocalApiCode.OK, call(WIFI_SET_RADIO, ENABLED to false))
        assertFalse(wifi.radioOn)
        assertCode(LocalApiCode.OK, call(WIFI_SET_RADIO, ENABLED to true))
        assertTrue(wifi.radioOn)
        assertCode(LocalApiCode.BAD_REQUEST, call(WIFI_SET_RADIO))
        wifi.radioAccepts = false
        assertCode(LocalApiCode.REFUSED, call(WIFI_SET_RADIO, ENABLED to false))
    }

    // --- device.reboot ---

    @Test fun `reboot asks Android to reboot the device`() {
        val r = call(DEVICE_REBOOT)
        assertCode(LocalApiCode.OK, r)
        assertEquals(1, platform.reboots.get())
        assertTrue("a reboot touches no Wi-Fi: ${wifi.calls}", wifi.calls.isEmpty())
    }

    @Test fun `a reboot that goes ahead is OK once the refusal window has passed`() {
        // Android blocks the caller of a reboot that goes ahead until the device is down: the reply cannot wait for it.
        val gate = CountDownLatch(1)
        platform.rebootBlocksUntil = gate
        val api = LocalApi(platform, wifi, wifi, events, rebootRefusalWindowMs = 50)
        try {
            val r = runBlocking { api.call(DEVICE_REBOOT, APP, LocalApiArgs()) }
            assertCode(LocalApiCode.OK, r)
            assertEquals(1, platform.reboots.get())
            assertFalse("the reply waited for the reboot to return", platform.rebootReturned)
            // The lock is free again: the blocked reboot does not hold up the next call.
            assertCode(LocalApiCode.OK, runBlocking { api.call(WIFI_SAVED, APP, LocalApiArgs()) })
        } finally {
            gate.countDown()
        }
    }

    @Test fun `a reboot Android refuses during a phone call is refused`() {
        platform.rebootThrows = IllegalStateException("Cannot be called with ongoing call on the device")
        val r = call(DEVICE_REBOOT)
        assertCode(LocalApiCode.REFUSED, r)
        assertFalse(r.ok)
    }

    @Test fun `any other reboot failure is an error naming its class, never its message`() {
        platform.rebootThrows = SecurityException("admin $SECRET")
        val r = call(DEVICE_REBOOT)
        assertCode(LocalApiCode.ERROR, r)
        assertEquals("device.reboot failed: SecurityException", r.message)
    }

    @Test fun `reboot ignores every extra`() {
        assertCode(LocalApiCode.OK, call(DEVICE_REBOOT, ENABLED to false, SSID to HOME))
        assertEquals(1, platform.reboots.get())
    }

    // --- failures, secrets, audit ---

    @Test fun `a device without Wi-Fi answers unsupported`() {
        val noWifi = LocalApi(platform, null, null, events)
        val r = runBlocking { noWifi.call(WIFI_SAVED, APP, LocalApiArgs()) }
        assertCode(LocalApiCode.UNSUPPORTED, r)
        assertCode(LocalApiCode.OK, runBlocking { noWifi.call(DESCRIBE, APP, LocalApiArgs()) })
    }

    @Test fun `an exception is an error naming its class, never its message`() {
        wifi.addThrows = IllegalArgumentException("bad key $SECRET for $HOME")
        val r = connect()
        assertCode(LocalApiCode.ERROR, r)
        assertEquals("wifi.connect failed: IllegalArgumentException", r.message)
    }

    @Test fun `the password never appears in a reply, and the SSID never in a message`() {
        val replies = mutableListOf<LocalApiReply>()
        replies += connect()
        replies += connect(security = "sae")
        replies += connect(security = "wep", password = SECRET)
        replies += connect(ssid = "")
        replies += connect(security = "wpa3")
        wifi.enableAccepts = false
        replies += connect()
        wifi.addAccepts = false
        replies += connect()
        wifi.addThrows = IllegalStateException("$SECRET $HOME")
        replies += connect()
        replies += call(WIFI_FORGET, SSID to HOME, PASSWORD to SECRET)
        replies += call(WIFI_FORGET, SSID to "Nowhere", PASSWORD to SECRET)
        replies += call(WIFI_SET_AUTO_JOIN, SSID to HOME, ENABLED to false, PASSWORD to SECRET)
        replies += call(WIFI_SET_AUTO_JOIN, SSID to "Nowhere", ENABLED to true)
        replies += call(WIFI_SAVED, PASSWORD to SECRET)
        replies += call(DESCRIBE, PASSWORD to SECRET)
        platform.installer = null
        replies += connect()

        replies.forEach { r ->
            val all = r.fields().values.joinToString(" ")
            assertFalse("password in ${r.code} reply: $all", SECRET in all)
            assertFalse("SSID in ${r.code} message: ${r.message}", HOME in r.message || "Nowhere" in r.message)
        }
    }

    @Test fun `changes are recorded as method and code only`() {
        connect()
        call(WIFI_SAVED)
        call(DESCRIBE)
        call(WIFI_SET_RADIO, ENABLED to true)
        call(DEVICE_REBOOT)
        platform.deviceOwner = false
        call(WIFI_FORGET, SSID to HOME)
        call(DEVICE_REBOOT)
        assertEquals(
            listOf(
                LocalApi.EVENT_TYPE to "wifi.connect OK",
                LocalApi.EVENT_TYPE to "wifi.setRadio OK",
                LocalApi.EVENT_TYPE to "device.reboot OK",
                LocalApi.EVENT_TYPE to "wifi.forget UNAUTHORIZED",
                LocalApi.EVENT_TYPE to "device.reboot UNAUTHORIZED",
            ),
            events.recorded,
        )
    }

    private companion object {
        const val APP = "com.hellonexi.deviceapp"
        const val AGENT = "com.mdmesh.agent"
        const val HOME = "HomeNet"
        const val SECRET = "hunter2-very-secret"
    }
}
