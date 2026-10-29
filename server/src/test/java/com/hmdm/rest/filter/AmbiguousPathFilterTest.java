package com.hmdm.rest.filter;

import org.junit.Test;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins {@link AmbiguousPathFilter}: a request path that Tomcat or Jersey would route differently from the way the
 * Guice filters match it (a {@code ;} path parameter, an encoded {@code ;}, {@code /}, {@code \} or {@code .}, an empty,
 * {@code .} or {@code ..} segment, a backslash) is refused with 400 before any other filter sees it, and every path the
 * console, the agent and file downloads use is passed on. Requests and responses are {@link Proxy} fakes.
 */
public class AmbiguousPathFilterTest {

    /** Spellings that reach a private resource (or could) while the auth filters' patterns don't match them. */
    private static final String[] REFUSED = {
            // path parameters: Tomcat and Jersey drop them, the Guice filter patterns don't
            "/rest/private;x=y/users/current",
            "/rest/private;/users/current",
            "/rest;x/private/users/current",
            "/rest/private/users;x/current",
            "/rest/private/users/current;x",
            "/rest/plugins/messaging/private;x/send",
            "/rest/plugins;x/messaging/private/send",
            "/rest/plugins/messaging;x/private/send",
            "/rest/notification/private;x/x",
            "/rest/plugin/main/private;x/active",
            "/rest/private;jsessionid=ABC/users/current",
            "/files;x/app.apk",
            "/files/app.apk;x",
            "/agent/ws/dev-1;x",
            "/rest/public/agent/v1/checkin;x",
            // encoded separators and dots, either case
            "/rest/private%3Bx=y/users/current",
            "/rest/private%3bx/users/current",
            "/rest%2Fprivate/users/current",
            "/rest/private%2fusers/current",
            "/rest%5Cprivate/users/current",
            "/rest%5cprivate/users/current",
            "/rest/%2E/private/users/current",
            "/rest/x/%2e%2e/private/users/current",
            "/rest/private%2E/users/current",
            "/rest/private%00/users/current",
            // empty, dot and dot-dot segments
            "/rest//private/users/current",
            "//rest/private/users/current",
            "/rest/private//users/current",
            "/rest/plugins//messaging/private/send",
            "/rest/./private/users/current",
            "/rest/x/../private/users/current",
            "/rest/private/users/current/.",
            "/rest/private/users/current/..",
            "/files/../rest/private/users/current",
            "/files/./app.apk",
            // backslashes
            "/rest\\private/users/current",
            "/rest/private\\users/current",
    };

    /** What the console, the agent, the QR and file downloads send, and query strings with anything in them. */
    private static final String[] PASSED = {
            "/",
            "/index.html",
            "/assets/index-3f9c1a.js",
            "/devices/7c9e6679-7425-40de-944b-e07fc1f90ae7",
            "/rest/private/users/current",
            "/rest/private/users/current/",
            "/rest/private/devices/search",
            "/rest/private/agent/v1/devices/1/state",
            "/rest/plugins/messaging/private/send",
            "/rest/plugins/deviceinfo/deviceinfo-plugin-settings/private",
            "/rest/plugin/main/private/active",
            "/rest/public/auth/login",
            "/rest/public/jwt/login",
            "/rest/public/name",
            "/rest/public/agent/v1/enroll",
            "/rest/public/agent/v1/checkin",
            "/rest/public/passwordReset/reset/abc",
            "/agent/ws/7c9e6679-7425-40de-944b-e07fc1f90ae7?secret=a;b%3B%2F",
            "/files/agent.apk",
            "/files/app-1.2.3.apk",
            "/files/customer_1/sub/app_v2.apk",
            "/files/my%20app.apk",
            "/files/%C3%A9t%C3%A9.apk",
            "/files/a%2Bb.apk",
            "/files/100%25.apk",
            "/files/.well-known.apk",
            "/files/app..apk",
            "/files/app.apk?sig=1;2&x=%2F..%5C",
            "/rest/private/devices/number/ABC-123?x=%3B",
            "/update/status",
            "/recovery",
    };

    @Test
    public void ambiguousSpellingsAreRefusedWith400() throws Exception {
        for (String uri : REFUSED) {
            Outcome o = run("", uri);
            assertEquals(uri, 400, o.status);
            assertFalse(uri + " must not reach the next filter", o.passed);
        }
    }

    @Test
    public void everyRealRouteIsPassedOn() throws Exception {
        for (String uri : PASSED) {
            Outcome o = run("", uri);
            assertTrue(uri + " must be passed on", o.passed);
            assertEquals(uri, 0, o.status);
        }
    }

    @Test
    public void aContextPathIsLeftOutOfTheCheck() throws Exception {
        assertTrue(run("/mdm", "/mdm/rest/private/users/current").passed);
        assertEquals(400, run("/mdm", "/mdm/rest/private;x/users/current").status);
    }

    @Test
    public void onlyTheRawPathIsChecked() throws Exception {
        // The query string is not part of getRequestURI(): what the client puts there is the resource's business.
        assertTrue(AmbiguousPathFilter.isUnambiguous("/rest/public/name"));
        assertFalse(AmbiguousPathFilter.isUnambiguous("/rest/public/name;"));
        assertFalse(AmbiguousPathFilter.isUnambiguous(null));
    }

    // ---- fakes ------------------------------------------------------------------------------------------------------

    private static final class Outcome {
        int status;
        boolean passed;
    }

    private static Outcome run(String contextPath, String requestUri) throws Exception {
        String uri = requestUri.contains("?") ? requestUri.substring(0, requestUri.indexOf('?')) : requestUri;
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "getRequestURI": return uri;
                        case "getContextPath": return contextPath;
                        default: return null;
                    }
                });
        Outcome outcome = new Outcome();
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (p, m, a) -> {
                    if (m.getName().equals("setStatus") || m.getName().equals("sendError")) {
                        outcome.status = (Integer) a[0];
                    }
                    return null;
                });
        FilterChain chain = (req, res) -> outcome.passed = true;
        new AmbiguousPathFilter().doFilter(request, response, chain);
        return outcome;
    }
}
