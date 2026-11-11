/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import com.hmdm.rest.filter.BaseIPFilter;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * An audit record names the client, not the proxy in front of the server: on Docker the edge (a private-network
 * peer, {@code proxy.private.networks}), on a native install a proxy on this host or one listed in
 * {@code proxy.addresses}. A client that reaches Tomcat directly can't pick its address with a header.
 */
public class AuditClientAddressTest {

    private static final String EDGE = "172.18.0.5";      // the Docker edge, as the server sees it
    private static final String SERVER = "172.18.0.3";    // the server container's own address
    private static final String CLIENT = "203.0.113.7";

    @Test
    public void onDockerTheEdgesForwardedForNamesTheClient() {
        HttpServletRequest r = request(EDGE, SERVER, header("X-Forwarded-For", CLIENT));
        assertEquals(CLIENT, address(r, "", true));
    }

    @Test
    public void onlyTheLastForwardedForEntryCounts() {
        // the edge appends its view of the client; anything before it is what the client sent
        HttpServletRequest r = request(EDGE, SERVER, header("X-Forwarded-For", "198.51.100.66, " + CLIENT));
        assertEquals(CLIENT, address(r, "", true));
    }

    @Test
    public void withoutPrivateNetworksAPrivatePeerIsTheClient() {
        // a native install: a LAN client reaching Tomcat directly can't name another address
        HttpServletRequest r = request("192.168.1.20", "192.168.1.5", header("X-Forwarded-For", CLIENT));
        assertEquals("192.168.1.20", address(r, "", false));
    }

    @Test
    public void aListedProxyOnAnotherHostNamesTheClient() {
        HttpServletRequest r = request("198.51.100.10", "198.51.100.20", header("X-Forwarded-For", CLIENT));
        assertEquals(CLIENT, address(r, "198.51.100.10", false));
    }

    @Test
    public void anUnlistedPublicPeerIsTheClient() {
        HttpServletRequest r = request("198.51.100.99", SERVER, header("X-Forwarded-For", CLIENT));
        assertEquals("198.51.100.99", address(r, "198.51.100.10", true));
    }

    @Test
    public void aLocalProxySendingOnlyXRealIpStillNamesTheClient() {
        // the legacy rule: a proxy on this host that sends only proxy.ip.header (X-Real-IP by default)
        HttpServletRequest r = request("127.0.0.1", "127.0.0.1", header("X-Real-IP", CLIENT));
        assertEquals(CLIENT, address(r, "", false));
    }

    @Test
    public void aLocalProxySendingXRealIpBeatsAForwardedForTheClientSent() {
        // nginx with only `proxy_set_header X-Real-IP $remote_addr;` passes on whatever X-Forwarded-For the client sent
        Map<String, String> h = header("X-Real-IP", CLIENT);
        h.put("X-Forwarded-For", "10.9.9.9");
        HttpServletRequest r = request("127.0.0.1", "127.0.0.1", h);
        assertEquals(CLIENT, address(r, "", false));
    }

    @Test
    public void aProxyThatNamesNoClientLeavesThePeer() {
        HttpServletRequest r = request(EDGE, SERVER, Collections.emptyMap());
        assertEquals(EDGE, address(r, "", true));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static String address(HttpServletRequest request, String proxyAddresses, boolean privatePeersAreProxies) {
        List<String> proxies = proxyAddresses.isEmpty()
                ? Collections.emptyList() : Arrays.asList(proxyAddresses.split(","));
        return ResourceAuditor.clientAddress(request, new BaseIPFilter("", proxyAddresses, "X-Real-IP"), proxies,
                privatePeersAreProxies);
    }

    private static Map<String, String> header(String name, String value) {
        Map<String, String> h = new HashMap<>();
        h.put(name, value);
        return h;
    }

    private static HttpServletRequest request(String peer, String local, Map<String, String> headers) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                AuditClientAddressTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getRemoteAddr": return peer;
                        case "getLocalAddr": return local;
                        case "getHeader": return headers.get((String) args[0]);
                        case "getHeaders": {
                            String v = headers.get((String) args[0]);
                            return Collections.enumeration(v == null
                                    ? Collections.<String>emptyList() : Collections.singletonList(v));
                        }
                        default: return null;
                    }
                });
    }
}
