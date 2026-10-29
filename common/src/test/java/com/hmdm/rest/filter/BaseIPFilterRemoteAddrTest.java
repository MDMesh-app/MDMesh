/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.filter;

import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The address the Headwind-era resolver (audit log, IP allowlists) takes from a proxy's header. */
public class BaseIPFilterRemoteAddrTest {

    /** A request from {@code peer} to {@code local}, carrying {@code value} in header {@code name} (null: absent). */
    private static HttpServletRequest request(String peer, String local, String name, String value) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getRemoteAddr":
                            return peer;
                        case "getLocalAddr":
                            return local;
                        case "getHeader":
                            return name.equalsIgnoreCase((String) args[0]) ? value : null;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    /** From nginx on the same host (peer = local = loopback) with an X-Real-IP header. */
    private static HttpServletRequest sameHost(String realIp) {
        return request("127.0.0.1", "127.0.0.1", "X-Real-IP", realIp);
    }

    private final BaseIPFilter resolver = new BaseIPFilter("", "10.0.0.5", "");

    @Test
    public void aProxysAddressHeaderIsUsed() {
        assertEquals("203.0.113.7", resolver.getRemoteAddr(sameHost("203.0.113.7")));
        assertEquals("2001:db8::7", resolver.getRemoteAddr(request("10.0.0.5", "10.0.0.9", "X-Real-IP", " 2001:db8::7 ")));
        // A list: the last entry, the one the proxy added (as AgentResource reads X-Forwarded-For).
        assertEquals("203.0.113.7", resolver.getRemoteAddr(sameHost("6.6.6.6, 203.0.113.7")));
    }

    @Test
    public void aHeaderFromAnyOtherPeerIsIgnored() {
        assertEquals("198.51.100.1",
                resolver.getRemoteAddr(request("198.51.100.1", "10.0.0.9", "X-Real-IP", "203.0.113.7")));
    }

    @Test
    public void forDisplayAHeaderThatIsNotAnAddressGivesThePeer() {
        assertEquals("127.0.0.1", resolver.getRemoteAddr(sameHost("example.com")));
        assertEquals("127.0.0.1", resolver.getRemoteAddr(sameHost("203.0.113.7:4711")));
        assertEquals("127.0.0.1", resolver.getRemoteAddr(sameHost("")));
        assertEquals("127.0.0.1", resolver.getRemoteAddr(sameHost(null)));
    }

    @Test
    public void theAllowlistBlocksAProxyHeaderThatIsNotAnAddress() {
        BaseIPFilter allow = new BaseIPFilter("127.0.0.1,192.168.0.0/16", "", "");
        for (String bad : new String[]{"garbage", "6.6.6.6,", "1.2.3.4:5", "[2001:db8::1]", "", " "}) {
            assertFalse("'" + bad + "'", allow.match(sameHost(bad)));
        }
    }

    @Test
    public void theAllowlistChecksTheProxyReportedAddress() {
        BaseIPFilter allow = new BaseIPFilter("127.0.0.1,192.168.0.0/16", "", "");
        assertTrue(allow.match(sameHost("192.168.4.4")));
        assertFalse(allow.match(sameHost("203.0.113.7")));
        // No header from the proxy: the peer itself, as before.
        assertTrue(allow.match(sameHost(null)));
    }

    @Test
    public void anAppendedForwardedForMatchesOnlyItsLastEntry() {
        // proxy.ip.header=X-Forwarded-For behind nginx's $proxy_add_x_forwarded_for: the client controls the prefix.
        HttpServletRequest r = request("127.0.0.1", "127.0.0.1", "X-Forwarded-For", "6.6.6.6, 203.0.113.7");
        assertFalse(new BaseIPFilter("127.0.0.1", "", "X-Forwarded-For").match(r));
        assertFalse(new BaseIPFilter("6.6.6.6", "", "X-Forwarded-For").match(r));
        assertTrue(new BaseIPFilter("203.0.113.7/32", "", "X-Forwarded-For").match(r));
    }
}
