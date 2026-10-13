package com.mdmesh.policy.wifi

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.UserRestrictions
import org.junit.Assert.assertEquals
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
        assertTrue(o is PolicyOutcome.Failed)
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
}
