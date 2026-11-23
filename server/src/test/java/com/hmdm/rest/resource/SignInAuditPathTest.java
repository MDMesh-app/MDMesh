/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import com.hmdm.auth.LocalAuth;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.persistence.mapper.CommonMapper;
import com.hmdm.persistence.mapper.CustomerMapper;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.plugin.persistence.mapper.PluginMapper;
import com.hmdm.plugin.service.PluginStatusCache;
import com.hmdm.plugins.audit.persistence.AuditDAO;
import com.hmdm.plugins.audit.persistence.domain.AuditLogRecord;
import com.hmdm.plugins.audit.rest.filter.AuditFilter;
import com.hmdm.rest.json.UserCredentials;
import com.hmdm.security.LoginThrottle;
import com.hmdm.security.jwt.TokenProvider;
import com.hmdm.security.jwt.rest.JWTAuthResource;
import com.hmdm.service.EmailService;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.FakeSessions;
import com.hmdm.util.BackgroundTaskRunnerService;
import com.hmdm.util.PasswordUtil;
import org.junit.Test;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Both sign-in routes through the real pieces: {@link AuditFilter}, the real resources and the JSON provider the
 * resources get their credentials from. The filter chain stands in only for Jersey's dispatch: it binds the body with
 * that provider, calls the resource, and answers as Jersey does (a body it can't bind is a 400, an exception from the
 * resource a sendError and a ServletException).
 */
public class SignInAuditPathTest {

    private static final String CONSOLE = "/rest/public/auth/login";
    private static final String API = "/rest/public/jwt/login";
    private static final String[] ROUTES = {CONSOLE, API};

    private static final String RIGHT_MD5 = "5F4DCC3B5AA765D61D8327DEB882CF99";
    private static final String WRONG_MD5 = "0123456789ABCDEF0123456789ABCDEF";
    private static final String PASSWORD_HASH = PasswordUtil.getHashFromMd5(RIGHT_MD5);
    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String NOT_STORED = "(not valid JSON, not stored)";

    /** Same quads as {@link #SEEN_KEY} once the parser has padded them, so it only fails on a name it hasn't seen. */
    private static final byte[] SIMILAR_KEY = {'"', 'z', 'z', 'z', 'z', (byte) 0xFF, 'z', 'z', 'z', '"'};
    private static final String SEEN_KEY = "zzzzzzz";

    /** One per test: the resource's provider has its own table of field names, as in a running server. */
    private final JacksonJaxbJsonProvider provider = new JacksonJaxbJsonProvider();
    private final List<AuditLogRecord> stored = new ArrayList<>();
    private Map<String, Object> attributes;
    private boolean lookupFails;

    private final UnsecureDAO dao = new UnsecureDAO(null, new Fakes.FakeMapper<>(UserMapper.class).mapper, null,
            new Fakes.FakeMapper<>(CommonMapper.class).mapper, null, null, null, null, null, null, null, null,
            new Fakes.FakeMapper<>(CustomerMapper.class).mapper, null, ".", 2, "com.hmdm.launcher") {
        @Override
        public User findByLoginOrEmail(String login) {
            if (lookupFails) {
                throw new IllegalStateException("lookup failed");
            }
            return "admin".equals(login) ? admin() : null;
        }
    };
    private final LoginThrottle throttle = new LoginThrottle("", false, SECRET, "http://localhost");
    private final BackgroundTaskRunnerService noTasks = new BackgroundTaskRunnerService() {
        @Override
        public void submitTask(Runnable task) {
        }
    };
    private final AuthResource console = new AuthResource(dao, null, dao, noTasks,
            new EmailService("", 25, false, false, "", "", "", "", "mdm@localhost", "", "", "", ""),
            null, false, new LocalAuth(dao), throttle);
    private final JWTAuthResource api = new JWTAuthResource(new TokenProvider(SECRET + SECRET, "3600", "3600"), dao,
            null, noTasks, throttle);

    @Test
    public void aFailedSignInNamesTheAccount() throws Exception {
        for (String route : ROUTES) {
            AuditLogRecord record = signIn(route, new FakeSessions(), plainBody(WRONG_MD5));

            assertEquals("admin", record.getLogin());
            assertEquals(route.equals(CONSOLE) ? Integer.valueOf(1) : Integer.valueOf(2), record.getErrorCode());
            assertTrue(record.getPayload(), record.getPayload().contains("\"login\":\"admin\""));
            assertNoPassword(record);
        }
    }

    @Test
    public void aBodyThatIsNotUtf8IsRefusedByTheResourceAndKeptByTheAudit() throws Exception {
        // Jackson (2.23 on) refuses invalid UTF-8 anywhere in a body, a key the binding skips or has seen too: the
        // resource never runs, nobody signs in, so the record names no account. It is kept: route, error, the note.
        teach(SEEN_KEY);
        byte[][] bodies = {
                join("{\"login\":\"admin\",\"password\":\"" + WRONG_MD5 + "\",", SIMILAR_KEY, ":1}"),
                join("{", SIMILAR_KEY, ":1,\"login\":\"admin\",\"password\":\"" + WRONG_MD5 + "\"}"),
                join("{\"login\":\"admin\",\"email\":\"a", new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80},
                        "\",\"password\":\"" + RIGHT_MD5 + "\"}")};
        for (String route : ROUTES) {
            for (byte[] body : bodies) {
                AuditLogRecord record = signIn(route, new FakeSessions(), body);

                assertNull(record.getLogin());
                assertEquals("refused before the resource ran", Integer.valueOf(2), record.getErrorCode());
                assertFalse(attributes.containsKey(UserCredentials.REQUEST_LOGIN));
                assertTrue(record.getPayload(), record.getPayload().startsWith("Method: POST\nURI: " + route));
                assertTrue(record.getPayload(), record.getPayload().contains(NOT_STORED));
                assertNoPassword(record);
            }
        }
    }

    @Test
    public void aSignInCantHideItsLoginBehindASiblingTheAuditDoesntKeep() throws Exception {
        // The resource binds it; the audit keeps only the first 8 KB, and the login comes after them
        for (String route : ROUTES) {
            AuditLogRecord record = signIn(route, new FakeSessions(), padded(WRONG_MD5));

            assertEquals("admin", record.getLogin());
            assertEquals(route.equals(CONSOLE) ? Integer.valueOf(1) : Integer.valueOf(2), record.getErrorCode());
            assertTrue(record.getPayload(), record.getPayload().contains("(truncated)"));
            assertNoPassword(record);
        }
    }

    @Test
    public void aSuccessfulSignInNamesTheAccount() throws Exception {
        for (String route : ROUTES) {
            AuditLogRecord record = signIn(route, new FakeSessions(), padded(RIGHT_MD5));

            assertEquals("admin", record.getLogin());
            assertEquals(Integer.valueOf(7), record.getUserId());
            assertEquals(Integer.valueOf(0), record.getErrorCode());
            assertNoPassword(record);
        }
    }

    @Test
    public void aSignInOverTheLimitNamesTheAccount() throws Exception {
        for (int i = 0; i < 10; i++) {
            signIn(CONSOLE, new FakeSessions(), plainBody(WRONG_MD5));
        }
        byte[] body = padded(WRONG_MD5);

        AuditLogRecord refused = signIn(CONSOLE, new FakeSessions(), body);
        assertEquals("admin", refused.getLogin());
        assertEquals(Integer.valueOf(1), refused.getErrorCode());

        refused = signIn(API, new FakeSessions(), body);
        assertEquals("admin", refused.getLogin());
        assertEquals("a 429", Integer.valueOf(2), refused.getErrorCode());
    }

    @Test
    public void aSignInThatThrowsNamesTheAccount() throws Exception {
        lookupFails = true;
        for (String route : ROUTES) {
            AuditLogRecord record = signIn(route, new FakeSessions(), padded(WRONG_MD5));

            assertEquals("admin", record.getLogin());
            assertEquals(Integer.valueOf(2), record.getErrorCode());
            assertNoPassword(record);
        }
    }

    @Test
    public void aBodyTheResourceRefusedKeepsWhatTheBodySays() throws Exception {
        for (String route : ROUTES) {
            // Not bound: this provider hasn't seen the name either
            AuditLogRecord unbound = signIn(route, new FakeSessions(),
                    join("{\"login\":\"admin\",\"password\":\"" + WRONG_MD5 + "\",", SIMILAR_KEY, ":1}"));
            assertNull(unbound.getLogin());
            assertEquals(Integer.valueOf(2), unbound.getErrorCode());
            assertTrue(unbound.getPayload(), unbound.getPayload().contains(NOT_STORED));

            // Bound, and refused by the resource for having no password
            AuditLogRecord noPassword = signIn(route, new FakeSessions(),
                    "{\"login\":\"admin\"}".getBytes(StandardCharsets.UTF_8));
            assertEquals("admin", noPassword.getLogin());
            assertFalse(attributes.containsKey(UserCredentials.REQUEST_LOGIN));

            // Not bound, as Jackson reads it: an object as the login. No login is named: nobody signed in
            AuditLogRecord unbindable = signIn(route, new FakeSessions(),
                    ("{\"login\":{\"a\":\"admin\"},\"password\":\"" + WRONG_MD5 + "\"}").getBytes(StandardCharsets.UTF_8));
            assertNull(unbindable.getLogin());
            assertEquals(Integer.valueOf(2), unbindable.getErrorCode());
            assertFalse(attributes.containsKey(UserCredentials.REQUEST_LOGIN));
            assertNoPassword(unbindable);
        }
    }

    @Test
    public void aSessionUserIsStillTheOneNamed() throws Exception {
        for (String route : ROUTES) {
            FakeSessions sessions = new FakeSessions();
            User sessionUser = admin();
            sessionUser.setId(9);
            sessionUser.setLogin("signed-in");
            sessionUser.setCustomerId(4);
            sessions.existing("browser").attributes.put("credentials", sessionUser);

            AuditLogRecord record = signIn(route, sessions, plainBody(WRONG_MD5));

            assertEquals("signed-in", record.getLogin());
            assertEquals(Integer.valueOf(9), record.getUserId());
            assertEquals(4, record.getCustomerId());
        }
    }

    @Test
    public void onlyTheLoginIsLeftOnTheRequest() throws Exception {
        for (String route : ROUTES) {
            signIn(route, new FakeSessions(), plainBody(WRONG_MD5));

            assertEquals(Collections.singletonMap(UserCredentials.REQUEST_LOGIN, "admin"), attributes);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    private AuditLogRecord signIn(String route, FakeSessions sessions, byte[] body) throws Exception {
        attributes = new HashMap<>();
        stored.clear();
        PluginStatusCache plugins = new PluginStatusCache((PluginMapper) Proxy.newProxyInstance(
                PluginMapper.class.getClassLoader(), new Class<?>[]{PluginMapper.class},
                (p, m, a) -> Collections.emptyList())) {
            @Override
            public boolean isPluginDisabled(String pluginIdentifier) {
                return false;
            }
        };
        AuditDAO auditDAO = new AuditDAO(null) {
            @Override
            public void insertAuditLogRecord(AuditLogRecord logRecord) {
                stored.add(logRecord);
            }
        };
        BackgroundTaskRunnerService runNow = new BackgroundTaskRunnerService() {
            @Override
            public void submitTask(Runnable task) {
                task.run();
            }
        };
        new AuditFilter(auditDAO, runNow, plugins, "", "", false)
                .doFilter(request(route, sessions, body), response(), jersey(route));
        assertEquals("one record", 1, stored.size());
        return stored.get(0);
    }

    /** As Jersey dispatches a sign-in. */
    private FilterChain jersey(String route) {
        return (servletRequest, servletResponse) -> {
            HttpServletRequest req = (HttpServletRequest) servletRequest;
            HttpServletResponse res = (HttpServletResponse) servletResponse;
            UserCredentials credentials;
            try {
                credentials = (UserCredentials) read(UserCredentials.class, req.getInputStream());
            } catch (IOException e) {
                res.sendError(400);
                return;
            }
            try {
                if (route.equals(CONSOLE)) {
                    com.hmdm.rest.json.Response answer = console.login(credentials, req, res);
                    res.setStatus(200);
                    res.getOutputStream().write(("{\"status\":\"" + answer.getStatus() + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
                } else {
                    javax.ws.rs.core.Response answer = api.login(credentials, req);
                    if (answer.getStatus() != 200) {
                        res.sendError(answer.getStatus());
                        return;
                    }
                    res.setStatus(200);
                    res.getOutputStream().write(new ObjectMapper().writeValueAsBytes(answer.getEntity()));
                }
            } catch (RuntimeException e) {
                res.sendError(500, "Request failed.");
                throw new ServletException(e);
            }
        };
    }

    /** Puts a field name in the provider's table: any route it serves can do that, audited or not. */
    private void teach(String key) throws IOException {
        read(User.class, new ByteArrayInputStream(("{\"" + key + "\":1}").getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object read(Class<?> type, InputStream in) throws IOException {
        return provider.readFrom((Class) type, type, new Annotation[0], MediaType.APPLICATION_JSON_TYPE,
                new MultivaluedHashMap<>(), in);
    }

    private static void assertNoPassword(AuditLogRecord record) {
        for (String md5 : new String[]{RIGHT_MD5, WRONG_MD5}) {
            assertFalse(record.toLogString(), record.toLogString().contains(md5));
        }
    }

    /** A sign-in with a note over the audit's 8 KB first, then the password and the login. */
    private static byte[] padded(String password) {
        StringBuilder note = new StringBuilder();
        while (note.length() < 20_000) {
            note.append("padding ");
        }
        return ("{\"note\":\"" + note + "\",\"password\":\"" + password + "\",\"login\":\"admin\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] plainBody(String password) {
        return ("{\"login\":\"admin\",\"password\":\"" + password + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] join(String start, byte[] middle, String end) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(start.getBytes(StandardCharsets.UTF_8), 0, start.length());
        out.write(middle, 0, middle.length);
        out.write(end.getBytes(StandardCharsets.UTF_8), 0, end.length());
        return out.toByteArray();
    }

    private static User admin() {
        UserRole role = new UserRole();
        role.setId(2);
        User user = new User();
        user.setId(7);
        user.setLogin("admin");
        user.setCustomerId(1);
        user.setUserRole(role);
        user.setPassword(PASSWORD_HASH);
        user.setAuthToken("token");
        return user;
    }

    private HttpServletRequest request(String route, FakeSessions sessions, byte[] body) {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        ServletInputStream stream = new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getMethod": return "POST";
                        case "getRequestURI": return route;
                        case "getContextPath": return "";
                        case "getInputStream": return stream;
                        case "getRemoteAddr": return "192.0.2.10";
                        case "getLocalAddr": return "192.0.2.1";
                        case "getHeaders": return Collections.emptyEnumeration();
                        case "getSession": return sessions.getSession(a);
                        case "getAttribute": return attributes.get((String) a[0]);
                        case "setAttribute":
                            attributes.put((String) a[0], a[1]);
                            return null;
                        default: return null;
                    }
                });
    }

    private static HttpServletResponse response() {
        ServletOutputStream stream = new ServletOutputStream() {
            @Override
            public void write(int b) {
            }
        };
        return (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (p, m, a) -> "getOutputStream".equals(m.getName()) ? stream : null);
    }
}
