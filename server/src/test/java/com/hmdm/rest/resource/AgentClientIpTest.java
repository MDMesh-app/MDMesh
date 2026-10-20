/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The device public address the server records: which forwarding header it believes, from whom, and what it drops. */
public class AgentClientIpTest {

    /** A request from {@code peer} with the given header lines (name, value, name, value...). */
    private static HttpServletRequest request(String peer, String... headers) {
        Map<String, List<String>> h = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            h.computeIfAbsent(headers[i].toLowerCase(), k -> new ArrayList<>()).add(headers[i + 1]);
        }
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getRemoteAddr":
                            return peer;
                        case "getHeader": {
                            List<String> v = h.get(((String) args[0]).toLowerCase());
                            return v == null ? null : v.get(0);
                        }
                        case "getHeaders":
                            return Collections.enumeration(
                                    h.getOrDefault(((String) args[0]).toLowerCase(), Collections.emptyList()));
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    @Test
    public void noRequestRecordsNothing() {
        assertNull(AgentResource.clientIp(null));
    }

    @Test
    public void aPublicPeerIsRecordedAndItsHeadersIgnored() {
        assertEquals("203.0.113.7", AgentResource.clientIp(request("203.0.113.7",
                "CF-Connecting-IP", "6.6.6.6", "X-Forwarded-For", "7.7.7.7")));
    }

    @Test
    public void theEdgesSingleForwardedForIsRecorded() {
        assertEquals("203.0.113.7", AgentResource.clientIp(request("172.18.0.5", "X-Forwarded-For", "203.0.113.7")));
        assertEquals("2001:db8::7", AgentResource.clientIp(request("172.18.0.5", "X-Forwarded-For", " 2001:db8::7 ")));
    }

    @Test
    public void theLastForwardedForEntryWinsNotTheClientsFirst() {
        // nginx's $proxy_add_x_forwarded_for appends what it saw to whatever the client sent.
        assertEquals("203.0.113.7", AgentResource.clientIp(request("127.0.0.1",
                "X-Forwarded-For", "6.6.6.6, 203.0.113.7")));
        // Several header lines: the last line's last entry.
        assertEquals("203.0.113.7", AgentResource.clientIp(request("127.0.0.1",
                "X-Forwarded-For", "6.6.6.6", "X-Forwarded-For", "7.7.7.7, 203.0.113.7")));
    }

    @Test
    public void cfConnectingIpFromALocalPeerComesFirst() {
        assertEquals("198.51.100.9", AgentResource.clientIp(request("127.0.0.1",
                "CF-Connecting-IP", "198.51.100.9", "X-Forwarded-For", "6.6.6.6, 198.51.100.9")));
        assertEquals("198.51.100.9", AgentResource.clientIp(request("::1", "CF-Connecting-IP", "198.51.100.9")));
        assertEquals("198.51.100.9", AgentResource.clientIp(request("fd12::3", "CF-Connecting-IP", "198.51.100.9")));
    }

    @Test
    public void aValueThatIsNotAnAddressIsNeverRecorded() {
        // A bad CF-Connecting-IP falls through to X-Forwarded-For.
        assertEquals("203.0.113.7", AgentResource.clientIp(request("10.0.0.2",
                "CF-Connecting-IP", "<script>", "X-Forwarded-For", "203.0.113.7")));
        // A bad last entry falls back to the peer, never to the client-supplied entries before it.
        assertEquals("10.0.0.2", AgentResource.clientIp(request("10.0.0.2", "X-Forwarded-For", "6.6.6.6, unknown")));
        assertEquals("10.0.0.2", AgentResource.clientIp(request("10.0.0.2", "X-Forwarded-For", "6.6.6.6,")));
        assertEquals("10.0.0.2", AgentResource.clientIp(request("10.0.0.2", "X-Forwarded-For", "203.0.113.7:4711")));
        assertEquals("10.0.0.2", AgentResource.clientIp(request("10.0.0.2", "X-Forwarded-For", "example.com")));
        assertEquals("10.0.0.2", AgentResource.clientIp(request("10.0.0.2",
                "CF-Connecting-IP", "6.6.6.6.6", "X-Forwarded-For", "")));
        assertEquals("10.0.0.2", AgentResource.clientIp(request("10.0.0.2", "X-Forwarded-For", "1.2.3")));
    }

    @Test
    public void noHeadersRecordsThePeer() {
        assertEquals("192.168.1.20", AgentResource.clientIp(request("192.168.1.20")));
    }
}
