/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import com.hmdm.persistence.domain.User;
import com.hmdm.plugins.audit.persistence.domain.AuditLogRecord;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.jwt.JWTFilter;
import org.junit.After;
import org.junit.Test;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * An audit record names the user the request ran as, and files it under that user's customer: a console user (HTTP
 * session) and an API client (bearer token, no session) alike. Only a request nobody signed in for goes to the
 * default customer with no user.
 */
public class AuditActorTest {

    private static final String URI = "/rest/private/agent/v1/devices/h0001/commands";

    @After
    public void releaseContext() {
        SecurityContext.release();
    }

    @Test
    public void anApiClientIsRecordedAsItsUserInItsCustomer() throws Exception {
        // AuditFilter runs inside JWTFilter for /rest/private/*: the token's user is in the security context
        User apiUser = user(41, "api-bot", 7);
        AuditLogRecord record = audit(null, null, () -> SecurityContext.init(apiUser));

        assertEquals(7, record.getCustomerId());
        assertEquals("api-bot", record.getLogin());
        assertEquals(Integer.valueOf(41), record.getUserId());
    }

    @Test
    public void anApiClientIsRecordedEvenWhenJwtFilterRanInsideTheAuditFilter() throws Exception {
        // JWTFilter has released the context by the time the record is made; it left the user on the request
        AuditLogRecord record = audit(null, user(42, "ci-runner", 9), null);

        assertEquals(9, record.getCustomerId());
        assertEquals("ci-runner", record.getLogin());
        assertEquals(Integer.valueOf(42), record.getUserId());
    }

    @Test
    public void aConsoleUserIsRecordedFromTheSession() throws Exception {
        AuditLogRecord record = audit(user(5, "alice", 3), null, null);

        assertEquals(3, record.getCustomerId());
        assertEquals("alice", record.getLogin());
        assertEquals(Integer.valueOf(5), record.getUserId());
    }

    @Test
    public void theUserTheRequestRanAsWinsOverAStaleSession() throws Exception {
        // AuthFilter lets a request with a security context through without reading the session
        User apiUser = user(41, "api-bot", 7);
        AuditLogRecord record = audit(user(5, "alice", 3), null, () -> SecurityContext.init(apiUser));

        assertEquals(7, record.getCustomerId());
        assertEquals("api-bot", record.getLogin());
    }

    @Test
    public void aCustomerOnlyContextIsNotAUser() throws Exception {
        AuditLogRecord record = audit(null, null, () -> SecurityContext.init(4));

        assertEquals(1, record.getCustomerId());
        assertNull(record.getLogin());
        assertNull(record.getUserId());
    }

    @Test
    public void aRequestNobodySignedInForGoesToTheDefaultCustomer() throws Exception {
        AuditLogRecord record = audit(null, null, null);

        assertEquals(1, record.getCustomerId());
        assertNull(record.getLogin());
        assertNull(record.getUserId());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static User user(int id, String login, int customerId) {
        User u = new User();
        u.setId(id);
        u.setLogin(login);
        u.setCustomerId(customerId);
        return u;
    }

    /**
     * Runs a device command through its audit rule, as AuditFilter does: {@code sessionUser} in the HTTP session,
     * {@code tokenUser} where JWTFilter leaves it on the request, and {@code context} set up before the record is
     * made (as the auth filters around AuditFilter do).
     */
    private static AuditLogRecord audit(User sessionUser, User tokenUser, Runnable context)
            throws IOException, ServletException {
        byte[] body = "{\"type\":\"device.alert\"}".getBytes(StandardCharsets.UTF_8);
        ResourceAuditInfo info = ResourceAuditInfo.findAuditInfo("POST", URI).get();
        FilterChain resource = (req, res) -> {
            ((HttpServletResponse) res).setStatus(200);
            res.getOutputStream().write("{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8));
        };
        if (context != null) {
            context.run();
        }
        ResourceAuditor auditor = info.getResourceAuditor(request(body, sessionUser, tokenUser), response(), resource,
                "", "X-Real-IP");
        auditor.doProcess();
        return auditor.getAuditLogRecord();
    }

    private static HttpServletRequest request(byte[] body, User sessionUser, User tokenUser) {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        ServletInputStream servletInputStream = new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }
        };
        Map<String, Object> attributes = new HashMap<>();
        if (tokenUser != null) {
            attributes.put(JWTFilter.REQUEST_USER, tokenUser);
        }
        HttpSession session = sessionUser == null ? null : (HttpSession) Proxy.newProxyInstance(
                AuditActorTest.class.getClassLoader(), new Class<?>[]{HttpSession.class},
                (p, m, a) -> "getAttribute".equals(m.getName()) && "credentials".equals(a[0]) ? sessionUser : null);
        return (HttpServletRequest) Proxy.newProxyInstance(
                AuditActorTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMethod": return "POST";
                        case "getRequestURI": return URI;
                        case "getInputStream": return servletInputStream;
                        case "getRemoteAddr": return "192.0.2.10";
                        case "getSession": return session;
                        case "getAttribute": return attributes.get(args[0]);
                        default: return null;
                    }
                });
    }

    private static HttpServletResponse response() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ServletOutputStream servletOutputStream = new ServletOutputStream() {
            @Override
            public void write(int b) {
                out.write(b);
            }
        };
        return (HttpServletResponse) Proxy.newProxyInstance(
                AuditActorTest.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> "getOutputStream".equals(method.getName()) ? servletOutputStream : null);
    }
}
