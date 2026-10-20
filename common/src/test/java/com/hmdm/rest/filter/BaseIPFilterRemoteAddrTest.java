/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.filter;

import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;

/** The address the Headwind-era resolver (audit log, IP whitelists) takes from a proxy's header. */
public class BaseIPFilterRemoteAddrTest {

    private static HttpServletRequest request(String peer, String local, String realIp) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getRemoteAddr":
                            return peer;
                        case "getLocalAddr":
                            return local;
                        case "getHeader":
                            return "X-Real-IP".equalsIgnoreCase((String) args[0]) ? realIp : null;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private final BaseIPFilter resolver = new BaseIPFilter("", "10.0.0.5", "");

    @Test
    public void aProxysAddressHeaderIsUsed() {
        assertEquals("203.0.113.7", resolver.getRemoteAddr(request("127.0.0.1", "127.0.0.1", "203.0.113.7")));
        assertEquals("2001:db8::7", resolver.getRemoteAddr(request("10.0.0.5", "10.0.0.9", " 2001:db8::7 ")));
    }

    @Test
    public void aHeaderFromAnyOtherPeerIsIgnored() {
        assertEquals("198.51.100.1", resolver.getRemoteAddr(request("198.51.100.1", "10.0.0.9", "203.0.113.7")));
    }

    @Test
    public void aHeaderThatIsNotAnAddressIsIgnored() {
        assertEquals("127.0.0.1", resolver.getRemoteAddr(request("127.0.0.1", "127.0.0.1", "example.com")));
        assertEquals("127.0.0.1", resolver.getRemoteAddr(request("127.0.0.1", "127.0.0.1", "6.6.6.6, 203.0.113.7")));
        assertEquals("127.0.0.1", resolver.getRemoteAddr(request("127.0.0.1", "127.0.0.1", "")));
        assertEquals("127.0.0.1", resolver.getRemoteAddr(request("127.0.0.1", "127.0.0.1", null)));
    }
}
