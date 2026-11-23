/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import com.hmdm.persistence.domain.User;
import com.hmdm.plugins.audit.persistence.domain.AuditLogRecord;
import com.hmdm.rest.json.UserCredentials;
import com.hmdm.security.jwt.JWTFilter;
import org.junit.Test;

import javax.servlet.FilterChain;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A sign-in record names the login the resource ran, and only the body's when the resource left none. */
public class SignInLoginAuditTest {

    private static final String[] ROUTES = {"/rest/public/auth/login", "/rest/public/jwt/login"};
    private static final String MD5 = "5F4DCC3B5AA765D61D8327DEB882CF99";
    /** Not valid UTF-8, and a name this auditor's parser has not seen. */
    private static final byte[] UNREADABLE = ("{\"login\":\"admin\",\"password\":\"" + MD5 + "\",\"yyyy\u0000yyy\":1}")
            .getBytes(StandardCharsets.ISO_8859_1);

    static {
        UNREADABLE[new String(UNREADABLE, StandardCharsets.ISO_8859_1).indexOf(0)] = (byte) 0xFF;
    }

    private final Map<String, Object> attributes = new HashMap<>();
    private HttpSession session;

    @Test
    public void theLoginTheResourceRanIsPreferredOverTheBody() throws Exception {
        byte[] body = ("{\"login\":\"from-body\",\"password\":\"" + MD5 + "\"}").getBytes(StandardCharsets.UTF_8);
        for (String route : ROUTES) {
            AuditLogRecord record = audit(route, body, "from-resource");

            assertEquals("from-resource", record.getLogin());
            assertTrue(record.getPayload(), record.getPayload().contains("\"login\":\"from-body\""));
            assertFalse(record.toLogString(), record.toLogString().contains(MD5));
        }
    }

    @Test
    public void aBodyTheAuditorCannotReadStillNamesTheLoginTheResourceRan() throws Exception {
        for (String route : ROUTES) {
            attributes.clear();
            assertNull("no login from the body", audit(route, UNREADABLE, null).getLogin());

            AuditLogRecord record = audit(route, UNREADABLE, "admin");

            assertEquals("admin", record.getLogin());
            assertTrue(record.getPayload(), record.getPayload().contains("(not valid JSON, not stored)"));
            assertFalse(record.toLogString(), record.toLogString().contains(MD5));
        }
    }

    @Test
    public void withoutTheAttributeTheBodyStillNamesTheAccount() throws Exception {
        byte[] body = ("{\"login\":\"from-body\"}").getBytes(StandardCharsets.UTF_8);
        for (String route : ROUTES) {
            assertEquals("from-body", audit(route, body, null).getLogin());
        }
    }

    @Test
    public void aValueThatIsNotAStringIsNotALogin() throws Exception {
        attributes.put(UserCredentials.REQUEST_LOGIN, new StringBuilder("not-a-login"));
        byte[] body = ("{\"login\":\"from-body\"}").getBytes(StandardCharsets.UTF_8);

        assertEquals("from-body", audit(ROUTES[0], body, null).getLogin());
    }

    @Test
    public void aSignedInUserIsPreferredOverTheAttribute() throws Exception {
        byte[] body = ("{\"login\":\"from-body\"}").getBytes(StandardCharsets.UTF_8);

        attributes.put(JWTFilter.REQUEST_USER, user("token-user"));
        assertEquals("token-user", audit(ROUTES[1], body, "from-resource").getLogin());

        attributes.clear();
        User sessionUser = user("session-user");
        session = (HttpSession) Proxy.newProxyInstance(HttpSession.class.getClassLoader(),
                new Class<?>[]{HttpSession.class},
                (p, m, a) -> "getAttribute".equals(m.getName()) && "credentials".equals(a[0]) ? sessionUser : null);
        assertEquals("session-user", audit(ROUTES[0], body, "from-resource").getLogin());
    }

    private AuditLogRecord audit(String route, byte[] body, String loginFromResource) throws Exception {
        FilterChain resource = (req, res) -> {
            if (loginFromResource != null) {
                req.setAttribute(UserCredentials.REQUEST_LOGIN, loginFromResource);
            }
            ((HttpServletResponse) res).setStatus(200);
            res.getOutputStream().write("{\"status\":\"ERROR\"}".getBytes(StandardCharsets.UTF_8));
        };
        ResourceAuditor auditor = ResourceAuditInfo.findAuditInfo("POST", route).get()
                .getResourceAuditor(request(route, body), response(), resource, "", "X-Real-IP");
        auditor.doProcess();
        return auditor.getAuditLogRecord();
    }

    private static User user(String login) {
        User user = new User();
        user.setId(5);
        user.setLogin(login);
        user.setCustomerId(2);
        return user;
    }

    private HttpServletRequest request(String route, byte[] body) throws IOException {
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
                        case "getInputStream": return stream;
                        case "getRemoteAddr": return "192.0.2.10";
                        case "getLocalAddr": return "192.0.2.1";
                        case "getSession": return session;
                        case "getAttribute": return attributes.get(a[0]);
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
