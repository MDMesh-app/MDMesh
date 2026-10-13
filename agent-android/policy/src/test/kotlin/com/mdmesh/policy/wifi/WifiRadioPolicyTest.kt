package com.mdmesh.policy.wifi

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.UserRestrictions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `wifiRadio` decision logic over a fake radio and restriction sink (no Android types): the radio is only
 * switched off when another network keeps the device reachable, and Android 13+ also locks the user's toggle.
 */
class WifiRadioPolicyTest {

    private class FakeRadio(
        var on: Boolean,
        var otherNetwork: Boolean = false,
        var platformAccepts: Boolean = true,
    ) : WifiRadio {
        val calls = mutableListOf<Boolean>()
        override fun isEnabled() = on
        override fun setEnabled(enabled: Boolean): Boolean {
            calls += enabled
            if (platformAccepts) on = enabled
            return platformAccepts
        }
        override fun hasNonWifiInternet() = otherNetwork
    }

    private class FakeRestrictions : (String, Boolean) -> Unit {
        val active = mutableSetOf<String>()
        override fun invoke(key: String, add: Boolean) { if (add) active += key else active -= key }
    }

    private fun policy(radio: WifiRadio, r: FakeRestrictions, sdk: Int) =
        WifiRadioPolicy(radio, r, sdk) { true }

    @Test fun `off with another network turns the radio off and locks the toggle on Android 13+`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val r = FakeRestrictions()
        assertEquals(PolicyOutcome.Applied, policy(radio, r, 33).setEnabled(false))
        assertEquals(false, radio.on)
        assertEquals(setOf(UserRestrictions.DISALLOW_CHANGE_WIFI_STATE), r.active)
    }

    @Test fun `off is refused while Wi-Fi is the only network, so the device stays reachable`() {
        val radio = FakeRadio(on = true, otherNetwork = false); val r = FakeRestrictions()
        val o = policy(radio, r, 34).setEnabled(false)
        assertTrue("deferred, not failed: the rest of the configuration must still apply", o is PolicyOutcome.Deferred)
        assertEquals(true, radio.on)
        assertTrue("radio untouched", radio.calls.isEmpty())
        assertTrue("nothing locked either", r.active.isEmpty())
    }

    @Test fun `off when the radio is already off only locks (no connectivity check needed)`() {
        val radio = FakeRadio(on = false, otherNetwork = false); val r = FakeRestrictions()
        assertEquals(PolicyOutcome.Applied, policy(radio, r, 33).setEnabled(false))
        assertTrue(radio.calls.isEmpty())
        assertEquals(setOf(UserRestrictions.DISALLOW_CHANGE_WIFI_STATE), r.active)
    }

    @Test fun `below Android 13 there is no toggle lock, only the radio switch`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val r = FakeRestrictions()
        assertEquals(PolicyOutcome.Applied, policy(radio, r, 30).setEnabled(false))
        assertEquals(false, radio.on)
        assertTrue(r.active.isEmpty())
    }

    @Test fun `on releases the lock and turns the radio on`() {
        val radio = FakeRadio(on = false)
        val r = FakeRestrictions().apply { active += UserRestrictions.DISALLOW_CHANGE_WIFI_STATE }
        assertEquals(PolicyOutcome.Applied, policy(radio, r, 33).setEnabled(true))
        assertEquals(true, radio.on)
        assertTrue(r.active.isEmpty())
    }

    @Test fun `a platform refusal is reported, not swallowed`() {
        val radio = FakeRadio(on = false, platformAccepts = false); val r = FakeRestrictions()
        assertTrue(policy(radio, r, 33).setEnabled(true) is PolicyOutcome.Failed)
    }

    @Test fun `capability key is wifiRadio, distinct from the wifi settings lock`() {
        assertEquals("wifiRadio", policy(FakeRadio(on = true), FakeRestrictions(), 33).capabilityKey)
        assertEquals("no_change_wifi_state", UserRestrictions.DISALLOW_CHANGE_WIFI_STATE)
    }

    @Test fun `release lifts the toggle lock without touching the radio`() {
        val radio = FakeRadio(on = false)
        val r = FakeRestrictions().apply { active += UserRestrictions.DISALLOW_CHANGE_WIFI_STATE }
        assertEquals(PolicyOutcome.Applied, policy(radio, r, 33).release())
        assertTrue(r.active.isEmpty())
        assertTrue(radio.calls.isEmpty())
    }

    private var now = 0L
    private val min = 60_000L

    private fun healing(radio: WifiRadio, r: FakeRestrictions, state: WifiRadioOffState, sdk: Int = 33) =
        WifiRadioPolicy(radio, r, sdk, state, { now }) { true }

    @Test fun `deferred Off says no other network`() {
        val o = policy(FakeRadio(on = true, otherNetwork = false), FakeRestrictions(), 33).setEnabled(false)
        assertTrue((o as PolicyOutcome.Deferred).reason.startsWith("no other network"))
    }

    @Test fun `Wi-Fi comes back on when the server is unreachable, even with validated cellular`() {
        // The server is only reachable over the Wi-Fi LAN: cellular is up, but no check-in gets through.
        val radio = FakeRadio(on = true, otherNetwork = true); val r = FakeRestrictions()
        val state = InMemoryWifiRadioOffState(); val p = healing(radio, r, state)
        now = 100 * min
        assertEquals(PolicyOutcome.Applied, p.setEnabled(false))
        now += 9 * min
        assertFalse(p.selfHeal(lastCheckInOkMs = 100 * min))
        assertEquals(false, radio.on)
        now += min
        assertTrue("10 minutes without reaching the server: Wi-Fi back on", p.selfHeal(100 * min))
        assertEquals(true, radio.on)
        assertTrue("toggle lock lifted", r.active.isEmpty())
        assertTrue(state.heldOn)
        assertFalse("nothing more to heal", p.selfHeal(100 * min))
    }

    @Test fun `no heal within 10 minutes of turning off, even when the last check-in is older`() {
        val radio = FakeRadio(on = true, otherNetwork = true)
        val p = healing(radio, FakeRestrictions(), InMemoryWifiRadioOffState())
        now = 60 * min
        p.setEnabled(false)
        now += 9 * min
        assertFalse(p.selfHeal(lastCheckInOkMs = 0))
        assertFalse(p.selfHeal(lastCheckInOkMs = null))
        assertEquals(false, radio.on)
    }

    @Test fun `a check-in after turning off restarts the wait`() {
        val radio = FakeRadio(on = true, otherNetwork = true)
        val p = healing(radio, FakeRestrictions(), InMemoryWifiRadioOffState())
        p.setEnabled(false)
        now = 30 * min
        assertFalse("reached the server 5 minutes ago", p.selfHeal(lastCheckInOkMs = 25 * min))
        now = 35 * min
        assertTrue(p.selfHeal(lastCheckInOkMs = 25 * min))
    }

    @Test fun `a refused enable keeps the state and the next heartbeat retries`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val r = FakeRestrictions()
        val state = InMemoryWifiRadioOffState(); val p = healing(radio, r, state)
        p.setEnabled(false)
        radio.platformAccepts = false
        now = 20 * min
        assertFalse(p.selfHeal(null))
        assertTrue("lock lifted anyway, so the user can turn it on", r.active.isEmpty())
        assertEquals(0L, state.offSinceMs); assertFalse(state.heldOn)
        radio.platformAccepts = true
        now = 30 * min
        assertTrue(p.selfHeal(null))
        assertEquals(true, radio.on)
    }

    @Test fun `held on after a heal, Off is deferred until the configuration changes`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val r = FakeRestrictions()
        val state = InMemoryWifiRadioOffState(); val p = healing(radio, r, state)
        p.setEnabled(false); now = 10 * min; assertTrue(p.selfHeal(null))
        // A re-send or a boot re-apply (a new instance over the persisted state) must not strand it again.
        val again = healing(radio, r, state).setEnabled(false)
        assertTrue(again is PolicyOutcome.Deferred && again.reason.startsWith("no connection to the server"))
        assertEquals(true, radio.on); assertTrue(r.active.isEmpty())
        p.endHold()
        assertEquals(PolicyOutcome.Applied, p.setEnabled(false))
        assertEquals(false, radio.on)
    }

    @Test fun `On and Not managed end the hold`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val state = InMemoryWifiRadioOffState()
        val p = healing(radio, FakeRestrictions(), state)
        state.heldOn = true; p.setEnabled(true); assertFalse(state.heldOn)
        state.heldOn = true; p.release(); assertFalse(state.heldOn)
    }

    @Test fun `the off time survives a reboot and a boot re-apply does not restart it`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val state = InMemoryWifiRadioOffState()
        healing(radio, FakeRestrictions(), state).setEnabled(false)
        now = 8 * min
        val afterBoot = healing(radio, FakeRestrictions(), state)
        assertEquals(PolicyOutcome.Applied, afterBoot.setEnabled(false))
        now = 10 * min
        assertTrue(afterBoot.selfHeal(null))
    }

    @Test fun `the off time is recorded before the radio is switched, and cleared when the platform refuses`() {
        val state = InMemoryWifiRadioOffState()
        val radio = object : WifiRadio {
            var accept = true
            override fun isEnabled() = true
            override fun setEnabled(enabled: Boolean): Boolean {
                assertEquals("persisted before the switch", 7L, state.offSinceMs)
                return accept
            }
            override fun hasNonWifiInternet() = true
        }
        now = 7
        assertEquals(PolicyOutcome.Applied, healing(radio, FakeRestrictions(), state).setEnabled(false))
        state.offSinceMs = null; radio.accept = false
        assertTrue(healing(radio, FakeRestrictions(), state).setEnabled(false) is PolicyOutcome.Failed)
        assertEquals(null, state.offSinceMs)
    }

    @Test fun `no self-heal when the policy did not turn Wi-Fi off`() {
        val radio = FakeRadio(on = false, otherNetwork = false); val state = InMemoryWifiRadioOffState()
        val p = healing(radio, FakeRestrictions(), state)
        now = 60 * min
        assertFalse(p.selfHeal(null))
        assertEquals(false, radio.on)
        p.setEnabled(true); radio.on = false
        now = 120 * min
        assertFalse("On clears it", p.selfHeal(null))
    }

    @Test fun `a clock going back restarts the wait instead of blocking the heal`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val state = InMemoryWifiRadioOffState()
        val p = healing(radio, FakeRestrictions(), state)
        now = 1_000 * min
        p.setEnabled(false)
        now = 5 * min // e.g. the clock reset after the battery ran flat
        assertFalse(p.selfHeal(lastCheckInOkMs = 999 * min))
        assertEquals(5 * min, state.offSinceMs)
        now = 15 * min
        assertTrue("a check-in from before the jump is ignored", p.selfHeal(lastCheckInOkMs = 999 * min))
    }

    @Test fun `Not managed while Wi-Fi is off keeps the heal armed`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val r = FakeRestrictions()
        val state = InMemoryWifiRadioOffState(); val p = healing(radio, r, state)
        p.setEnabled(false)
        assertEquals(PolicyOutcome.Applied, p.release())
        assertTrue(r.active.isEmpty()); assertEquals(false, radio.on)
        now = 10 * min
        assertTrue(p.selfHeal(null)); assertEquals(true, radio.on)
    }

    @Test fun `Not managed with Wi-Fi on clears the off state`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val state = InMemoryWifiRadioOffState()
        val p = healing(radio, FakeRestrictions(), state)
        p.setEnabled(false); radio.on = true // the user turned it back on (below Android 13)
        p.release()
        assertEquals(null, state.offSinceMs)
    }

    @Test fun `a VPN is not another network (it usually runs over the Wi-Fi being turned off)`() {
        assertTrue(WifiRadioPolicy.isOtherInternet(internet = true, validated = true, wifi = false, vpn = false))
        assertFalse(WifiRadioPolicy.isOtherInternet(internet = true, validated = true, wifi = false, vpn = true))
        assertFalse(WifiRadioPolicy.isOtherInternet(internet = true, validated = true, wifi = true, vpn = false))
        assertFalse(WifiRadioPolicy.isOtherInternet(internet = true, validated = false, wifi = false, vpn = false))
    }
}
