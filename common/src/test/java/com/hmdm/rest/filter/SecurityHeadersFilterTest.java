package com.hmdm.rest.filter;

import org.junit.Test;

import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Every response carries nosniff, no-framing (both headers) and Referrer-Policy; HSTS only when base.url is https.
 */
public class SecurityHeadersFilterTest {

    @Test
    public void everyResponseGetsTheHeaders() throws Exception {
        Map<String, String> headers = run("http://192.168.1.10:8080");
        assertEquals("nosniff", headers.get("X-Content-Type-Options"));
        assertEquals("DENY", headers.get("X-Frame-Options"));
        assertEquals("frame-ancestors 'none'", headers.get("Content-Security-Policy"));
        assertEquals("same-origin", headers.get("Referrer-Policy"));
        assertNull("no HSTS for an http install", headers.get("Strict-Transport-Security"));
        assertTrue(headers.containsKey("passed"));
    }

    @Test
    public void anHttpsInstallAlsoGetsHsts() throws Exception {
        Map<String, String> headers = run("https://mdm.example.com");
        assertEquals("max-age=15552000", headers.get("Strict-Transport-Security"));
        assertEquals("DENY", headers.get("X-Frame-Options"));
    }

    @Test
    public void noBaseUrlMeansNoHsts() throws Exception {
        assertFalse(run(null).containsKey("Strict-Transport-Security"));
    }

    private static Map<String, String> run(String baseUrl) throws Exception {
        ServletContext context = (ServletContext) Proxy.newProxyInstance(ServletContext.class.getClassLoader(),
                new Class<?>[]{ServletContext.class},
                (p, m, a) -> m.getName().equals("getInitParameter") && "base.url".equals(a[0]) ? baseUrl : null);
        FilterConfig config = (FilterConfig) Proxy.newProxyInstance(FilterConfig.class.getClassLoader(),
                new Class<?>[]{FilterConfig.class}, (p, m, a) -> m.getName().equals("getServletContext") ? context : null);
        Map<String, String> headers = new HashMap<>();
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class}, (p, m, a) -> {
                    if (m.getName().equals("setHeader")) {
                        headers.put((String) a[0], (String) a[1]);
                    } else if (m.getName().equals("addHeader")) {
                        throw new AssertionError("addHeader would allow a second copy of " + a[0]);
                    }
                    return null;
                });
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> null);
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.init(config);
        filter.doFilter(request, response, (req, res) -> headers.put("passed", "yes"));
        return headers;
    }
}
