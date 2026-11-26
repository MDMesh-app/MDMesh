/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import com.hmdm.persistence.domain.User;
import com.hmdm.plugins.audit.persistence.domain.AuditLogRecord;
import com.hmdm.rest.json.UserCredentials;
import com.hmdm.security.SecurityContext;
import org.junit.After;
import org.junit.Test;

import javax.servlet.FilterChain;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Signing in as another account (an org admin as one of theirs, a super-admin as a customer's admin), a password reset
 * by link and a customer delete leave an audit record, which names who did it and never holds a password or token.
 */
public class AccountAccessAuditTest {

    private static final String OLD_MD5 = "5F4DCC3B5AA765D61D8327DEB882CF99";
    private static final String NEW_MD5 = "0123456789ABCDEF0123456789ABCDEF";
    private static final String RESET_TOKEN = "reset-token-secret";

    @After
    public void releaseContext() {
        SecurityContext.release();
    }

    @Test
    public void theRoutesHaveARule() {
        assertEquals(Optional.of(ResourceAuditInfo.IMPERSONATE_USER),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/private/users/impersonate/11"));
        assertEquals(Optional.of(ResourceAuditInfo.IMPERSONATE_CUSTOMER),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/private/customers/impersonate/2"));
        assertEquals(Optional.of(ResourceAuditInfo.PASSWORD_RESET_BY_LINK),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/public/passwordReset/reset"));
        assertEquals(Optional.of(ResourceAuditInfo.REMOVE_CUSTOMER),
                ResourceAuditInfo.findAuditInfo("DELETE", "/rest/private/customers/2"));
        assertEquals(Optional.empty(), ResourceAuditInfo.findAuditInfo("POST", "/rest/private/users/impersonate/11/x"));
        assertEquals(Optional.empty(), ResourceAuditInfo.findAuditInfo("POST", "/rest/public/passwordReset/resetx"));
    }

    @Test
    public void anImpersonationNamesTheAdminWhoDidItNotTheAccount() throws Exception {
        for (String uri : new String[]{"/rest/private/users/impersonate/11",
                "/rest/private/customers/impersonate/2"}) {
            User admin = user(5, "alice", 3);
            User target = user(11, "bob", 3);
            Map<String, Object> session = new HashMap<>();
            session.put("credentials", admin);
            // The resource replaces the session with one signed in as the account
            AuditLogRecord record = run("POST", uri, "", session, null,
                    req -> session.put("credentials", target));

            assertEquals(uri.contains("customers") ? "plugin.audit.action.impersonate.customer"
                    : "plugin.audit.action.impersonate.user", record.getAction());
            assertEquals("alice", record.getLogin());
            assertEquals(Integer.valueOf(5), record.getUserId());
            assertTrue(record.getPayload(), record.getPayload().contains("URI: " + uri));
        }
    }

    @Test
    public void anImpersonationNamesTheAdminWhenTheRequestRanWithAContext() throws Exception {
        User admin = user(5, "alice", 3);
        Map<String, Object> session = new HashMap<>();
        session.put("credentials", admin);
        AuditLogRecord record = run("POST", "/rest/private/users/impersonate/11", "", session,
                () -> SecurityContext.init(admin), req -> session.put("credentials", user(11, "bob", 3)));

        assertEquals("alice", record.getLogin());
    }

    @Test
    public void aPasswordResetNamesTheAccountAndStoresNoTokenOrPassword() throws Exception {
        String body = "{\"passwordResetToken\":\"" + RESET_TOKEN + "\",\"newPassword\":\"" + NEW_MD5 + "\"}";
        AuditLogRecord record = run("POST", "/rest/public/passwordReset/reset", body, null, null,
                req -> req.setAttribute(UserCredentials.REQUEST_LOGIN, "bob"));

        assertEquals("plugin.audit.action.password.reset.link", record.getAction());
        assertEquals("bob", record.getLogin());
        assertFalse(record.toLogString(), record.toLogString().contains(RESET_TOKEN));
        assertFalse(record.toLogString(), record.toLogString().contains(NEW_MD5));
        String payload = record.getPayload() == null ? "" : record.getPayload();
        assertFalse(payload, payload.contains(RESET_TOKEN));
        assertFalse(payload, payload.contains(NEW_MD5));
    }

    @Test
    public void aCustomerDeleteIsRecordedWithoutThePassword() throws Exception {
        Map<String, Object> session = new HashMap<>();
        session.put("credentials", user(1, "admin", 1));
        AuditLogRecord record = run("DELETE", "/rest/private/customers/2", "{\"oldPassword\":\"" + OLD_MD5 + "\"}",
                session, null, req -> { });

        assertEquals("plugin.audit.action.remove.customer", record.getAction());
        assertTrue(record.getPayload(), record.getPayload().contains("/rest/private/customers/2"));
        assertTrue(record.getPayload(), record.getPayload().contains("\"oldPassword\":\"******\""));
        assertFalse(record.toLogString(), record.toLogString().contains(OLD_MD5));
    }

    private interface Resource {
        void run(HttpServletRequest req);
    }

    private static AuditLogRecord run(String method, String uri, String body, Map<String, Object> session,
                                      Runnable before, Resource resource) throws Exception {
        if (before != null) {
            before.run();
        }
        byte[] sent = body.getBytes(StandardCharsets.UTF_8);
        ResourceAuditInfo info = ResourceAuditInfo.findAuditInfo(method, uri).get();
        FilterChain chain = (req, res) -> {
            resource.run((HttpServletRequest) req);
            ((HttpServletResponse) res).setStatus(200);
            res.getOutputStream().write("{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8));
        };
        ResourceAuditor auditor = info.getResourceAuditor(request(method, uri, sent, session), response(), chain, "",
                "X-Real-IP");
        auditor.doProcess();
        AuditLogRecord record = auditor.getAuditLogRecord();
        assertEquals(Integer.valueOf(0), record.getErrorCode());
        return record;
    }

    private static User user(int id, String login, int customerId) {
        User u = new User();
        u.setId(id);
        u.setLogin(login);
        u.setCustomerId(customerId);
        return u;
    }

    private static HttpServletRequest request(String httpMethod, String uri, byte[] body,
                                              Map<String, Object> sessionAttributes) {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        ServletInputStream input = new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }
        };
        HttpSession session = sessionAttributes == null ? null : (HttpSession) Proxy.newProxyInstance(
                AccountAccessAuditTest.class.getClassLoader(), new Class<?>[]{HttpSession.class},
                (proxy, method, args) -> "getAttribute".equals(method.getName())
                        ? sessionAttributes.get((String) args[0]) : null);
        Map<String, Object> attributes = new HashMap<>();
        return (HttpServletRequest) Proxy.newProxyInstance(
                AccountAccessAuditTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMethod":
                            return httpMethod;
                        case "getRequestURI":
                            return uri;
                        case "getInputStream":
                            return input;
                        case "getRemoteAddr":
                            return "192.0.2.10";
                        case "getLocalAddr":
                            return "192.0.2.1";
                        case "getSession":
                            return session;
                        case "getAttribute":
                            return attributes.get((String) args[0]);
                        case "setAttribute":
                            attributes.put((String) args[0], args[1]);
                            return null;
                        default:
                            return null;
                    }
                });
    }

    private static HttpServletResponse response() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ServletOutputStream output = new ServletOutputStream() {
            @Override
            public void write(int b) {
                out.write(b);
            }
        };
        int[] status = {200};
        return (HttpServletResponse) Proxy.newProxyInstance(
                AccountAccessAuditTest.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getOutputStream":
                            return output;
                        case "setStatus":
                            status[0] = (Integer) args[0];
                            return null;
                        case "getStatus":
                            return status[0];
                        default:
                            return null;
                    }
                });
    }
}
