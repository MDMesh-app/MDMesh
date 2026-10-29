package com.hmdm.util;

import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Forwarding headers count only from the install's own proxy (a loopback or private peer). For a per-address limit, a
 * proxy that names no client gives no address, so its own address never becomes everyone's key; any other peer is the
 * client, whatever it sends.
 */
public class ClientAddressTest {

    private static final List<String> NONE = Collections.emptyList();

    @Test
    public void aPublicPeerIsItsOwnAddressWhateverItSends() {
        HttpServletRequest r = request("203.0.113.7", "CF-Connecting-IP", "6.6.6.6", "X-Forwarded-For", "7.7.7.7");
        assertEquals("203.0.113.7", ClientAddress.of(r));
        assertEquals("203.0.113.7", ClientAddress.forLimiting(r, NONE, true));
    }

    @Test
    public void behindTheDockerEdgeTheLastForwardedForEntryIsTheClient() {
        HttpServletRequest r = request("172.18.0.5", "X-Forwarded-For", "6.6.6.6, 198.51.100.4");
        assertEquals("198.51.100.4", ClientAddress.of(r));
        assertEquals("198.51.100.4", ClientAddress.forLimiting(r, NONE, true));
    }

    @Test
    public void aProxyOnTheSameHostIsAlwaysBelieved() {
        assertEquals("198.51.100.4",
                ClientAddress.forLimiting(request("127.0.0.1", "X-Forwarded-For", "198.51.100.4"), NONE, false));
        assertEquals("198.51.100.4",
                ClientAddress.forLimiting(request("::1", "X-Forwarded-For", "198.51.100.4"), NONE, false));
    }

    @Test
    public void onANativeInstallALanClientIsItsOwnAddressWhateverItSends() {
        // Tomcat's port reached directly from the LAN (a LAN-only install with no proxy): the peer is the client, so
        // it is limited by its own address, and a header it sends changes nothing, not even whether it is limited.
        assertEquals("192.168.1.50", ClientAddress.forLimiting(request("192.168.1.50"), NONE, false));
        HttpServletRequest r = request("192.168.1.50", "X-Forwarded-For", "10.9.9.9");
        assertEquals("192.168.1.50", ClientAddress.forLimiting(r, NONE, false));
        assertEquals("fd00::50", ClientAddress.forLimiting(request("fd00::50", "X-Forwarded-For", "10.9.9.9"), NONE,
                false));
        // A proxy the operator listed in proxy.addresses is believed.
        assertEquals("10.9.9.9", ClientAddress.forLimiting(r, Arrays.asList("192.168.1.50"), false));
    }

    @Test
    public void anUnlistedPrivatePeerWithASpoofedForwardedForIsKeyedByThePeer() {
        // Fails closed: a header from a private peer that isn't a listed proxy neither picks its address nor lets it
        // off the per-address limit (no null), so a LAN client can't spray passwords across accounts by sending one.
        // Behind an unlisted proxy every client shares the proxy's address, and the server logs a warning.
        for (String peer : Arrays.asList("192.168.1.50", "10.0.0.5", "172.16.3.4", "169.254.1.1", "fd00::50",
                "fe80::1")) {
            assertEquals(peer, ClientAddress.forLimiting(request(peer, "X-Forwarded-For", "203.0.113.9"), NONE, false));
            assertEquals(peer, ClientAddress.forLimiting(request(peer, "X-Forwarded-For", "1.2.3.4, 10.9.9.9"),
                    Arrays.asList("192.168.1.2"), false));
            assertEquals(peer, ClientAddress.forLimiting(request(peer, "X-Forwarded-For", "not-an-ip"), NONE, false));
        }
    }

    @Test
    public void cfConnectingIpNeverCountsForLimiting() {
        // A proxy that doesn't strip it would let any client choose its address.
        HttpServletRequest r = request("127.0.0.1", "CF-Connecting-IP", "203.0.113.9", "X-Forwarded-For", "198.51.100.4");
        assertEquals("198.51.100.4", ClientAddress.forLimiting(r, NONE, false));
        assertNull(ClientAddress.forLimiting(request("127.0.0.1", "CF-Connecting-IP", "203.0.113.9"), NONE, false));
    }

    @Test
    public void aProxyThatNamesNoClientGivesNoAddressForLimiting() {
        assertEquals("127.0.0.1", ClientAddress.of(request("127.0.0.1")));
        assertNull(ClientAddress.forLimiting(request("127.0.0.1"), NONE, true));
        assertNull(ClientAddress.forLimiting(request("10.0.0.2", "X-Forwarded-For", "not-an-ip"), NONE, true));
        assertNull(ClientAddress.forLimiting(request("fd00::1"), NONE, true));
        assertNull(ClientAddress.forLimiting(null, NONE, true));
    }

    private static HttpServletRequest request(String peer, String... headers) {
        Map<String, String> h = new HashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            h.put(headers[i].toLowerCase(), headers[i + 1]);
        }
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getRemoteAddr": return peer;
                        case "getHeader": return h.get(((String) a[0]).toLowerCase());
                        case "getHeaders": {
                            String v = h.get(((String) a[0]).toLowerCase());
                            return Collections.enumeration(v == null ? Collections.<String>emptyList() : Arrays.asList(v));
                        }
                        default: return null;
                    }
                });
    }
}
