package com.hmdm.rest.filter;

import org.junit.Test;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins {@link LegacyDeviceApiFilter}: the Headwind launcher's device routes answer 404 unless the
 * {@code legacy.device.api} context parameter turns them on, whichever way the path is spelled, and every other route
 * (the MDMesh agent's v1 API, the console's private routes, provisioning, file downloads, the wake socket) is passed on.
 * Requests, responses and the servlet context are hand-made {@link Proxy} fakes, so no container is needed.
 */
public class LegacyDeviceApiFilterTest {

    private static final String[] LEGACY = {
            "/rest/public/sync/configuration/dev-1",
            "/rest/public/sync/info",
            "/rest/public/sync/applicationSettings/dev-1",
            "/rest/public/sync",
            "/rest/notifications/device/dev-1",
            "/rest/notification/polling/dev-1",
            "/rest/plugins/deviceinfo/deviceinfo/public/dev-1",
            "/rest/plugins/deviceinfo/deviceinfo-plugin-settings/device/dev-1",
            "/rest/plugins/devicelog/log/list/dev-1",
            "/rest/plugins/devicelog/log/rules/dev-1",
            "/rest/plugins/messaging/public/status/1/2",
            "/rest/public/qr/abc",
            "/rest/public/qr/json/abc",
            "/rest/public/qr",
    };

    private static final String[] KEPT = {
            "/rest/public/agent/v1/enroll",
            "/rest/public/agent/v1/checkin",
            "/rest/public/qrcode",
            "/rest/public/name",
            "/rest/public/auth/login",
            "/rest/public/synchronize",
            "/rest/private/agent/v1/devices/1/state",
            "/rest/plugins/deviceinfo/deviceinfo/private/dev-1",
            "/rest/plugins/deviceinfo/deviceinfo-plugin-settings/private",
            "/rest/plugins/devicelog/log/private/search",
            "/rest/plugins/messaging/private/send",
            "/rest/plugin/main/public/registered",
            "/files/app.apk",
            "/agent/ws/dev-1",
            "/",
    };

    // ---- defaults ---------------------------------------------------------------------------------------------------

    @Test
    public void legacyRoutesAre404WhenTheParameterIsAbsent() throws Exception {
        LegacyDeviceApiFilter filter = filter(null);
        for (String path : LEGACY) {
            Outcome o = run(filter, "", path, path);
            assertEquals(path, 404, o.status);
            assertFalse(path, o.passed);
        }
    }

    @Test
    public void otherRoutesPassWhenTheParameterIsAbsent() throws Exception {
        LegacyDeviceApiFilter filter = filter(null);
        for (String path : KEPT) {
            Outcome o = run(filter, "", path, path);
            assertTrue(path, o.passed);
            assertEquals(path, 0, o.status);
        }
    }

    @Test
    public void onlyOneOrTrueTurnsThemOn() throws Exception {
        for (String off : new String[]{"", "0", "false", "no", "yes", "2", " "}) {
            assertEquals("value '" + off + "'", 404, run(filter(off), "", LEGACY[0], LEGACY[0]).status);
        }
        for (String on : new String[]{"1", "true", "TRUE", " true "}) {
            for (String path : LEGACY) {
                assertTrue("value '" + on + "' " + path, run(filter(on), "", path, path).passed);
            }
        }
    }

    // ---- spellings --------------------------------------------------------------------------------------------------

    @Test
    public void aContextPathIsStripped() throws Exception {
        Outcome o = run(filter(null), "/mdm", "/mdm/rest/public/sync/info", "/rest/public/sync/info");
        assertEquals(404, o.status);
        assertTrue(run(filter(null), "/mdm", "/mdm/rest/public/agent/v1/checkin", "/rest/public/agent/v1/checkin").passed);
    }

    @Test
    public void rawSpellingsOfALegacyPathAreCaught() throws Exception {
        // The container's decoded servlet path is the canonical one; the raw request URI is checked as well so that a
        // spelling the container leaves alone (but the REST layer would still route) cannot slip through.
        String[] raw = {
                "/rest/public/sync;jsessionid=x/info",
                "/rest/public;a=b/sync/info",
                "/rest//public/sync/info",
                "/rest/public//sync/info",
                "/rest/public/%73ync/info",
                "/rest/public/sync%2Finfo",
                "/rest/Public/Sync/info",
                "/rest/notification/polling;x/dev-1",
                "/rest/plugins/messaging/public/status/1/2?x=1",
        };
        for (String uri : raw) {
            // A servlet path the container could have produced for something unrelated: only the raw URI is legacy.
            Outcome o = run(filter(null), "", uri, "/elsewhere");
            assertEquals(uri, 404, o.status);
        }
    }

    @Test
    public void theDecodedServletPathIsCaughtEvenWhenTheRawUriLooksHarmless() throws Exception {
        Outcome o = run(filter(null), "", "/x", "/rest/public/sync/info");
        assertEquals(404, o.status);
    }

    @Test
    public void aServletPathPlusPathInfoIsJoined() throws Exception {
        Outcome o = run(filter(null), "", "/x", "/rest", "/public/sync/info");
        assertEquals(404, o.status);
    }

    // ---- fakes ------------------------------------------------------------------------------------------------------

    private static final class Outcome {
        int status;
        boolean passed;
    }

    private static LegacyDeviceApiFilter filter(String value) throws Exception {
        Map<String, String> params = new HashMap<>();
        if (value != null) {
            params.put(LegacyDeviceApiFilter.PARAMETER, value);
        }
        ServletContext context = (ServletContext) Proxy.newProxyInstance(
                ServletContext.class.getClassLoader(), new Class<?>[]{ServletContext.class},
                (p, m, a) -> {
                    if (m.getName().equals("getInitParameter")) {
                        return params.get((String) a[0]);
                    }
                    if (m.getName().equals("getInitParameterNames")) {
                        return Collections.enumeration(params.keySet());
                    }
                    return null;
                });
        FilterConfig config = (FilterConfig) Proxy.newProxyInstance(
                FilterConfig.class.getClassLoader(), new Class<?>[]{FilterConfig.class},
                (p, m, a) -> m.getName().equals("getServletContext") ? context : null);
        LegacyDeviceApiFilter filter = new LegacyDeviceApiFilter();
        filter.init(config);
        return filter;
    }

    private static Outcome run(LegacyDeviceApiFilter filter, String contextPath, String requestUri, String servletPath)
            throws Exception {
        return run(filter, contextPath, requestUri, servletPath, null);
    }

    private static Outcome run(LegacyDeviceApiFilter filter, String contextPath, String requestUri,
                               String servletPath, String pathInfo) throws Exception {
        String uri = requestUri.contains("?") ? requestUri.substring(0, requestUri.indexOf('?')) : requestUri;
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "getRequestURI": return uri;
                        case "getContextPath": return contextPath;
                        case "getServletPath": return servletPath;
                        case "getPathInfo": return pathInfo;
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
                    if (m.getName().equals("isCommitted")) {
                        return false;
                    }
                    return null;
                });
        FilterChain chain = (req, res) -> outcome.passed = true;
        filter.doFilter(request, response, chain);
        return outcome;
    }
}
