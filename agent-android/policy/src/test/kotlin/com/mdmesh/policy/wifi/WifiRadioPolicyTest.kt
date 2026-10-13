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

    private fun healing(radio: WifiRadio, r: FakeRestrictions, state: WifiRadioOffState, sdk: Int = 33) =
        WifiRadioPolicy(radio, r, sdk, state) { true }

    private val min = 60_000L

    @Test fun `deferred Off says no other network`() {
        val o = policy(FakeRadio(on = true, otherNetwork = false), FakeRestrictions(), 33).setEnabled(false)
        assertTrue((o as PolicyOutcome.Deferred).reason.startsWith("no other network"))
    }

    @Test fun `Off that strands the device is undone after 10 minutes without another network`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val r = FakeRestrictions()
        val state = InMemoryWifiRadioOffState()
        val p = healing(radio, r, state)
        assertEquals(PolicyOutcome.Applied, p.setEnabled(false))
        radio.otherNetwork = false // the SIM / Ethernet goes away
        assertFalse("first check only starts the clock", p.selfHeal(0))
        assertFalse(p.selfHeal(9 * min))
        assertEquals(false, radio.on)
        assertTrue("10 minutes offline: Wi-Fi back on", p.selfHeal(10 * min))
        assertEquals(true, radio.on)
        assertTrue("toggle lock lifted", r.active.isEmpty())
        assertFalse("not policy-off any more", state.offByPolicy)
        assertFalse("nothing more to heal", p.selfHeal(30 * min))
    }

    @Test fun `another network coming back restarts the clock`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val state = InMemoryWifiRadioOffState()
        val p = healing(radio, FakeRestrictions(), state)
        p.setEnabled(false)
        radio.otherNetwork = false; p.selfHeal(0)
        radio.otherNetwork = true; assertFalse(p.selfHeal(5 * min))
        radio.otherNetwork = false
        assertFalse(p.selfHeal(11 * min)); assertFalse(p.selfHeal(20 * min))
        assertTrue(p.selfHeal(21 * min))
    }

    @Test fun `no self-heal when the policy did not turn Wi-Fi off`() {
        val radio = FakeRadio(on = false, otherNetwork = false); val state = InMemoryWifiRadioOffState()
        val p = healing(radio, FakeRestrictions(), state)
        assertFalse(p.selfHeal(0)); assertFalse(p.selfHeal(60 * min))
        assertEquals(false, radio.on)
        p.setEnabled(true); radio.on = false
        assertFalse("On clears it", p.selfHeal(120 * min))
    }

    @Test fun `after a self-heal Off is deferred until another network exists, then applied`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val r = FakeRestrictions()
        val state = InMemoryWifiRadioOffState(); val p = healing(radio, r, state)
        p.setEnabled(false); radio.otherNetwork = false
        p.selfHeal(0); assertTrue(p.selfHeal(10 * min))
        val again = p.setEnabled(false)
        assertTrue(again is PolicyOutcome.Deferred && again.reason.startsWith("no other network"))
        assertEquals(true, radio.on)
        radio.otherNetwork = true
        assertEquals(PolicyOutcome.Applied, p.setEnabled(false))
        assertEquals(false, radio.on)
        assertTrue(state.offByPolicy)
    }

    @Test fun `the clock survives a reboot and a boot re-apply does not restart it`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val state = InMemoryWifiRadioOffState()
        healing(radio, FakeRestrictions(), state).setEnabled(false)
        radio.otherNetwork = false
        healing(radio, FakeRestrictions(), state).selfHeal(0)
        // Reboot: a new policy instance over the same persisted state; the boot re-apply asserts Off again.
        val afterBoot = healing(radio, FakeRestrictions(), state)
        assertEquals(PolicyOutcome.Applied, afterBoot.setEnabled(false))
        assertTrue(afterBoot.selfHeal(10 * min))
        assertEquals(true, radio.on)
    }

    @Test fun `release clears the policy-off state`() {
        val radio = FakeRadio(on = true, otherNetwork = true); val state = InMemoryWifiRadioOffState()
        val p = healing(radio, FakeRestrictions(), state)
        p.setEnabled(false); radio.otherNetwork = false
        p.release()
        assertFalse(p.selfHeal(0)); assertFalse(p.selfHeal(60 * min))
    }

    @Test fun `a VPN is not another network (it usually runs over the Wi-Fi being turned off)`() {
        assertTrue(WifiRadioPolicy.isOtherInternet(internet = true, validated = true, wifi = false, vpn = false))
        assertFalse(WifiRadioPolicy.isOtherInternet(internet = true, validated = true, wifi = false, vpn = true))
        assertFalse(WifiRadioPolicy.isOtherInternet(internet = true, validated = true, wifi = true, vpn = false))
        assertFalse(WifiRadioPolicy.isOtherInternet(internet = true, validated = false, wifi = false, vpn = false))
    }
}
