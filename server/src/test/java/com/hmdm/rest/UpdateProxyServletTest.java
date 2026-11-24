/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest;

import com.hmdm.security.ConsoleSession;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link UpdateProxyServlet} passes on only the supervisor routes the console and devices use, each with its one method
 * and gate, to a fixed upstream path; everything else is answered here and never reaches the supervisor. A stub
 * supervisor records what arrives.
 */
public class UpdateProxyServletTest {

    private HttpServer supervisor;
    private final List<String> forwarded = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, List<String>>> forwardedHeaders = Collections.synchronizedList(new ArrayList<>());
    private UpdateProxyServlet servlet;

    @Before
    public void startSupervisor() throws Exception {
        supervisor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        supervisor.createContext("/", ex -> {
            forwarded.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            forwardedHeaders.add(new HashMap<>(ex.getRequestHeaders()));
            byte[] body = ("{\"path\":\"" + ex.getRequestURI().getPath() + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if ("HEAD".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(200, -1);
            } else {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        supervisor.start();
        servlet = servletFor("http://127.0.0.1:" + supervisor.getAddress().getPort() + "/");
    }

    @After
    public void stopSupervisor() {
        supervisor.stop(0);
    }

    // ---- the allowlist ----------------------------------------------------------------------------------------------

    @Test
    public void theAgentApkIsPublicForGetAndHeadWithAnOptionalNumericVersion() throws Exception {
        assertEquals(200, call(get("/agent.apk")).status);
        assertEquals(200, call(get("/agent.apk").query("v=1234")).status);
        assertEquals(200, call(req("HEAD", "/agent.apk").query("v=7")).status);
        assertEquals(Arrays.asList("GET /update/agent.apk", "GET /update/agent.apk?v=1234", "HEAD /update/agent.apk?v=7"),
                forwarded);
    }

    @Test
    public void anAgentApkQueryOtherThanOneNumericVersionIs400AndNotForwarded() throws Exception {
        for (String q : new String[]{"v=abc", "v=", "v=1&v=2", "v=1&x=2", "x=1", "v=1234567890", "v=-1", "v=%31"}) {
            assertEquals(q, 400, call(get("/agent.apk").query(q)).status);
        }
        assertEquals(Collections.emptyList(), forwarded);
    }

    @Test
    public void theSupervisorHealthIsPublicAndGoesToItsHealthz() throws Exception {
        Result r = call(get("/healthz"));
        assertEquals(200, r.status);
        assertEquals(Arrays.asList("GET /healthz"), forwarded);
    }

    @Test
    public void statusNeedsTheSessionCheckToHaveRun() throws Exception {
        assertEquals("AuthFilter didn't pass the request: refused here too", 403, call(get("/status")).status);
        assertEquals(Collections.emptyList(), forwarded);

        Result r = call(get("/status").signedIn());
        assertEquals(200, r.status);
        assertEquals("{\"path\":\"/update/status\"}", r.body());
        assertEquals(Arrays.asList("GET /update/status"), forwarded);
    }

    @Test
    public void theActionsNeedASessionAndTheConsoleHeader() throws Exception {
        for (String action : new String[]{"/check", "/apply", "/auto", "/rollback"}) {
            assertEquals(action, 403, call(req("POST", action).header("X-MDMesh-Console", "1")).status);
            assertEquals(action, 403, call(req("POST", action).signedIn()).status);
            assertEquals(action, 403, call(req("POST", action).signedIn().header("X-MDMesh-Console", "true")).status);
        }
        assertEquals(Collections.emptyList(), forwarded);

        Result r = call(req("POST", "/auto").signedIn().header("X-MDMesh-Console", "1")
                .header("Content-Type", "application/json").body("{\"auto\":true}"));
        assertEquals(200, r.status);
        assertEquals(Arrays.asList("POST /update/auto"), forwarded);
    }

    @Test
    public void anyOtherPathIs404AndNeverForwarded() throws Exception {
        for (String path : new String[]{"/foo", "/status/", "/agent.apk/x", "/", "/recovery", "/statusx",
                "/update/status", "/healthz/x"}) {
            assertEquals(path, 404, call(get(path).signedIn()).status);
        }
        assertEquals("the bare /update (no path info)", 404, call(get(null).signedIn()).status);
        assertEquals(Collections.emptyList(), forwarded);
    }

    @Test
    public void aWrongMethodIs405AndNeverForwarded() throws Exception {
        assertEquals(405, call(req("POST", "/status").signedIn().header("X-MDMesh-Console", "1")).status);
        assertEquals(405, call(req("GET", "/apply").signedIn().header("X-MDMesh-Console", "1")).status);
        assertEquals(405, call(req("POST", "/agent.apk")).status);
        assertEquals(405, call(req("PUT", "/agent.apk")).status);
        assertEquals(405, call(req("DELETE", "/healthz")).status);
        assertEquals(Collections.emptyList(), forwarded);
    }

    // ---- what is forwarded ------------------------------------------------------------------------------------------

    @Test
    public void onlyTheSessionAndContentHeadersAreForwardedNeverTheRecoveryTokenOrAuthorization() throws Exception {
        call(req("POST", "/rollback").signedIn().header("X-MDMesh-Console", "1").header("Cookie", "JSESSIONID=abc")
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .header("X-Recovery-Token", "secret").header("Authorization", "Bearer x").header("X-Other", "y"));
        assertEquals(Arrays.asList("POST /update/rollback"), forwarded);
        Map<String, List<String>> h = forwardedHeaders.get(0);
        assertEquals(Arrays.asList("JSESSIONID=abc"), h.get("Cookie"));
        assertEquals(Arrays.asList("1"), h.get("X-mdmesh-console"));
        assertEquals(Arrays.asList("application/json"), h.get("Content-type"));
        assertNull("the recovery token works only on the host", h.get("X-recovery-token"));
        assertNull(h.get("Authorization"));
        assertNull(h.get("X-other"));
    }

    @Test
    public void anUnreachableSupervisorIs503Json() throws Exception {
        supervisor.stop(0);
        Result r = call(get("/status").signedIn());
        assertEquals(503, r.status);
        assertEquals("application/json", r.contentType);
        assertTrue(r.body(), r.body().contains("updater supervisor unreachable"));
    }

    @Test
    public void theAgentApkMayWaitLongerThanTheSupervisorsDownloadBound() {
        assertEquals("11 min: the supervisor may first download the agent, bounded at 10 min", 11 * 60 * 1000,
                UpdateProxyServlet.readTimeoutMs("/agent.apk"));
        assertEquals(60 * 1000, UpdateProxyServlet.readTimeoutMs("/status"));
    }

    @Test
    public void theDefaultSupervisorIsLoopback() throws Exception {
        assertEquals("http://127.0.0.1:9000", servletFor(null).supervisorBase());
        assertEquals("http://supervisor:9000", servletFor(" http://supervisor:9000// ").supervisorBase());
    }

    // ---- fakes ------------------------------------------------------------------------------------------------------

    private static UpdateProxyServlet servletFor(String base) throws Exception {
        ServletContext context = (ServletContext) Proxy.newProxyInstance(ServletContext.class.getClassLoader(),
                new Class<?>[]{ServletContext.class}, (p, m, a) ->
                        m.getName().equals("getInitParameter") && "supervisor.base".equals(a[0]) ? base : null);
        ServletConfig config = (ServletConfig) Proxy.newProxyInstance(ServletConfig.class.getClassLoader(),
                new Class<?>[]{ServletConfig.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getServletContext": return context;
                        case "getInitParameterNames": return Collections.emptyEnumeration();
                        default: return null;
                    }
                });
        UpdateProxyServlet servlet = new UpdateProxyServlet();
        servlet.init(config);
        return servlet;
    }

    private static Request get(String pathInfo) {
        return req("GET", pathInfo);
    }

    private static Request req(String method, String pathInfo) {
        return new Request(method, pathInfo);
    }

    private static final class Request {
        final String method;
        final String pathInfo;
        String query;
        boolean signedIn;
        byte[] body = new byte[0];
        final Map<String, List<String>> headers = new HashMap<>();

        Request(String method, String pathInfo) {
            this.method = method;
            this.pathInfo = pathInfo;
        }

        Request query(String q) {
            query = q;
            return this;
        }

        /** As AuthFilter leaves a request it passed on. */
        Request signedIn() {
            signedIn = true;
            return this;
        }

        Request header(String name, String value) {
            headers.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(value);
            return this;
        }

        Request body(String b) {
            body = b.getBytes(StandardCharsets.UTF_8);
            return this;
        }
    }

    private static final class Result {
        int status;
        String contentType;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        String body() {
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private Result call(Request r) throws Exception {
        InputStream in = new ByteArrayInputStream(r.body);
        ServletInputStream sin = new ServletInputStream() {
            @Override
            public int read() throws IOException {
                return in.read();
            }
        };
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getMethod": return r.method;
                        case "getPathInfo": return r.pathInfo;
                        case "getServletPath": return "/update";
                        case "getRequestURI": return "/update" + (r.pathInfo == null ? "" : r.pathInfo);
                        case "getContextPath": return "";
                        case "getQueryString": return r.query;
                        case "getInputStream": return sin;
                        case "getAttribute":
                            return r.signedIn && ConsoleSession.MARKERS.equals(a[0]) ? ConsoleSession.Markers.NONE : null;
                        case "getHeader": {
                            List<String> v = r.headers.get(((String) a[0]).toLowerCase());
                            return v == null ? null : v.get(0);
                        }
                        case "getHeaders": {
                            List<String> v = r.headers.get(((String) a[0]).toLowerCase());
                            return Collections.enumeration(v == null ? Collections.<String>emptyList() : v);
                        }
                        default: return null;
                    }
                });
        Result result = new Result();
        ServletOutputStream sout = new ServletOutputStream() {
            @Override
            public void write(int b) {
                result.out.write(b);
            }
        };
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "setStatus":
                        case "sendError":
                            result.status = (Integer) a[0];
                            return null;
                        case "setContentType":
                            result.contentType = (String) a[0];
                            return null;
                        case "getOutputStream": return sout;
                        default: return null;
                    }
                });
        servlet.service(request, response);
        assertFalse("a status was set", result.status == 0);
        return result;
    }
}
