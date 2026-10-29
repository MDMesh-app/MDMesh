package com.hmdm.util;

import org.junit.Test;

import javax.servlet.ServletContext;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * An install is HTTPS when its public address (base.url) is an https:// URL, and only then is the session cookie made
 * Secure: an HTTP install (a LAN address) must keep a cookie the browser sends over HTTP.
 */
public class SecureTransportTest {

    @Test
    public void onlyAnHttpsBaseUrlIsHttps() {
        assertTrue(SecureTransport.isHttps("https://mdm.example.com"));
        assertTrue(SecureTransport.isHttps("HTTPS://mdm.example.com/"));
        assertTrue(SecureTransport.isHttps("  https://10.0.0.5:8443"));
        assertFalse(SecureTransport.isHttps("http://192.168.1.10:8080"));
        assertFalse(SecureTransport.isHttps("http://https.example.com"));
        assertFalse(SecureTransport.isHttps(""));
        assertFalse(SecureTransport.isHttps((String) null));
    }

    @Test
    public void anHttpInstallLeavesTheCookieAlone() {
        // Returns before touching the cookie config at all (this context would fail if it were asked for one).
        assertFalse(SecureTransport.secureSessionCookie(context("http://192.168.1.10:8080")));
        assertFalse(SecureTransport.secureSessionCookie(context(null)));
    }

    @Test
    public void anHttpsInstallThatCannotMarkTheCookieSaysSo() {
        // The test classpath's servlet API (2.5) has no SessionCookieConfig: the failure is loud, never a silent
        // non-Secure cookie. Tomcat 9 has it (checked against a real Tomcat when this was written).
        try {
            SecureTransport.secureSessionCookie(context("https://mdm.example.com"));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Secure"));
        }
    }

    private static ServletContext context(String baseUrl) {
        return (ServletContext) Proxy.newProxyInstance(ServletContext.class.getClassLoader(),
                new Class<?>[]{ServletContext.class}, (p, m, a) -> {
                    if (m.getName().equals("getInitParameter")) {
                        return "base.url".equals(a[0]) ? baseUrl : null;
                    }
                    throw new AssertionError("unexpected " + m.getName());
                });
    }
}
