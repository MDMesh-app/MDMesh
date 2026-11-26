/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 * Modified by the MDMesh contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.plugins.audit.rest.filter;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.User;
import com.hmdm.plugins.audit.persistence.domain.AuditLogRecord;
import com.hmdm.plugins.audit.rest.AuditResource;
import com.hmdm.rest.filter.BaseIPFilter;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.UserCredentials;
import com.hmdm.security.ConsoleSession;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.jwt.JWTFilter;
import com.hmdm.util.ClientAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>An auditor for a single request-response chain.</p>
 *
 * @author isv
 */
class ResourceAuditor {

    private static final Logger logger = LoggerFactory.getLogger(ResourceAuditor.class);

    /**
     * <p>A name of session attribute holding the details for current user.</p>
     */
    private static final String sessionCredentials = "credentials";

    /**
     * <p>Reads request bodies with the same defaults as the JSON provider that hands them to the resource.</p>
     */
    private static final ObjectMapper COMMAND_MAPPER = new ObjectMapper();

    /**
     * <p>A key in message resource bundle referring to description of action mapped to audited request.</p>
     */
    private final String auditLogActionKey;

    /**
     * <p>An incoming request being processed.</p>
     */
    private final ServletRequest request;

    /**
     * <p>A response to be sent to client.</p>
     */
    private final ServletResponseAuditWrapper response;

    /**
     * <p>A filter chain</p>
     */
    private final FilterChain chain;

    /**
     * <p>How the request body is stored, or {@code null} if request data is not saved as a payload.</p>
     */
    private final AuditBody body;

    /** plugin_audit_log.login is VARCHAR(100). */
    static final int MAX_LOGIN = 100;

    /** The most of the body read for the record, 0 for no cap but {@link ServletRequestAuditWrapper#MAX_BODY}. */
    private final int bodyCap;

    /**
     * <p>A flag indicating if response is in Headwind MDM standard format and should be checked for errors.</p>
     */
    private final boolean checkResponse;

    /**
     * <p>The actions that sign the session in as another account. Their record names whoever was signed in before the
     * request ran: afterwards, the session is the other account's.</p>
     */
    private static final Set<String> IMPERSONATIONS = new HashSet<>(Arrays.asList(
            "plugin.audit.action.impersonate.user", "plugin.audit.action.impersonate.customer"));

    /** Who was signed in before the request ran, for {@link #IMPERSONATIONS}. */
    private User actorBefore;

    /**
     * In a session signed in as another account, the administrator who signed in as it, as the session was when the
     * request came: the actions taken in it are the administrator's, not the account's alone.
     */
    private Integer impersonatedBy;

    private final BaseIPFilter remoteAddrResolver;

    /** {@code proxy.addresses}, split: the proxies on other hosts whose X-Forwarded-For is believed. */
    private final List<String> trustedProxies;

    /** {@code proxy.private.networks}: any private-network peer is the install's proxy (Docker). */
    private final boolean privatePeersAreProxies;

    /**
     * <p>Constructs new <code>ResourceAuditor</code> instance. This implementation does nothing.</p>
     */
    ResourceAuditor(String auditLogActionKey, ServletRequest request, ServletResponse response,
                    FilterChain chain, AuditBody body, int bodyCap, boolean checkResponse,
                    String proxyIps, String ipHeader, List<String> trustedProxies, boolean privatePeersAreProxies)
            throws IOException {
        this.auditLogActionKey = auditLogActionKey;
        if (body != null) {
            // Wrap request only if we need to log it
            this.request = new ServletRequestAuditWrapper((HttpServletRequest)request);
        } else {
            this.request = request;
        }
        this.response = new ServletResponseAuditWrapper((HttpServletResponse)response);
        this.chain = chain;
        this.body = body;
        this.bodyCap = bodyCap;
        this.checkResponse = checkResponse;
        this.remoteAddrResolver = new BaseIPFilter("", proxyIps, ipHeader);
        this.trustedProxies = trustedProxies;
        this.privatePeersAreProxies = privatePeersAreProxies;
    }

    /** {@code proxy.addresses} split into its addresses, parsed once by the filter. */
    static List<String> proxyList(String proxyIps) {
        List<String> proxies = new ArrayList<>();
        for (String p : (proxyIps == null ? "" : proxyIps).split(",")) {
            if (!p.trim().isEmpty()) {
                proxies.add(p.trim());
            }
        }
        return Collections.unmodifiableList(proxies);
    }

    /**
     * <p>The client's address for the record. A proxy on this host or listed in {@code proxy.addresses} that sends
     * {@code proxy.ip.header} (X-Real-IP by default) is believed first, as before. Otherwise the sign-in limit's rule
     * applies ({@link ClientAddress#forLimiting}): the last X-Forwarded-For entry from a loopback peer, a listed proxy
     * or, with {@code proxy.private.networks} (Docker), any private-network peer. The legacy rule alone never believed
     * the Docker edge, so every record named the edge's container address.</p>
     */
    static String clientAddress(HttpServletRequest request, BaseIPFilter legacy, List<String> trustedProxies,
                                boolean privatePeersAreProxies) {
        if (legacy.isProxyNamingClient(request)) {
            return legacy.getRemoteAddr(request);
        }
        String address = ClientAddress.forLimiting(request, trustedProxies, privatePeersAreProxies);
        return address != null ? address : request.getRemoteAddr();
    }

    /**
     * <p>Executes the request/response chain and captures data necessary for auditing.</p>
     *
     * @throws IOException if an unexpected I/O error occurs.
     * @throws ServletException if an unexpected error occurs.
     */
    void doProcess() throws IOException, ServletException {
        if (IMPERSONATIONS.contains(auditLogActionKey)) {
            actorBefore = actingUser((HttpServletRequest) this.request);
        }
        impersonatedBy = ConsoleSession.impersonatorOf((HttpServletRequest) this.request);
        chain.doFilter(this.request, this.response);
    }

    /**
     * <p>Gets the audit log record for the target request/response chain.</p>
     *
     * @return an audit log record evaluated based on the request processing results or <code>null</code> if no such
     *         record is available.
     * @throws IOException if an unexpected I/O error occurs.
     */
    AuditLogRecord getAuditLogRecord() throws IOException {
        final HttpServletRequest httpRequest = (HttpServletRequest) this.request;
        final User currentUser = actorBefore != null ? actorBefore : actingUser(httpRequest);

        String action = this.auditLogActionKey;
        String payloadString = null;
        String requestedLogin = null;
        if (body != null) {
            ServletRequestAuditWrapper requestWrapper = (ServletRequestAuditWrapper)this.request;
            payloadString = "Method: " + requestWrapper.getMethod() + "\n" +
                    "URI: " + requestWrapper.getRequestURI();
            byte[] rawBody = requestWrapper.getRawBody();
            if (requestWrapper.isTooLarge()) {
                payloadString += "\nBody: " + AuditBody.TOO_LARGE;
            } else if (rawBody != null && rawBody.length > 0) {
                // Never the body as sent: AuditBody masks every one, and refuses one it can't parse
                payloadString += "\nBody: " + body.stored(rawBody, bodyCap);
                if (currentUser == null) {
                    requestedLogin = loginOf(rawBody);
                }
            }
            payloadString += "\nUser-Agent: " + httpRequest.getHeader("User-Agent");
        }

        AuditLogRecord logRecord = new AuditLogRecord();
        logRecord.setCreateTime(System.currentTimeMillis());
        logRecord.setIpAddress(clientAddress(httpRequest, remoteAddrResolver, trustedProxies, privatePeersAreProxies));
        if (currentUser != null) {
            logRecord.setCustomerId(currentUser.getCustomerId());
            logRecord.setLogin(currentUser.getLogin());
            logRecord.setUserId(currentUser.getId());
        } else {
            // 1 is the default customer ID, otherwise the record won't be visible
            logRecord.setCustomerId(1);
            // The login the sign-in resource ran. Only a body it refused leaves none, and then the body says what it can.
            Object resourceLogin = httpRequest.getAttribute(UserCredentials.REQUEST_LOGIN);
            String login = resourceLogin instanceof String ? (String) resourceLogin : requestedLogin;
            if (login != null) {
                // The column holds 100 characters; a sign-in can send any login, and the log line takes it too
                logRecord.setLogin(login.length() > MAX_LOGIN ? login.substring(0, MAX_LOGIN) : login);
            }
        }
        if (this.response.getStatus() == 200) {
            if (checkResponse && !isOk(this.response.getContent())) {
                logRecord.setErrorCode(1);
            } else {
                logRecord.setErrorCode(0);
            }
        } else {
            logRecord.setErrorCode(2);
        }
        if (body != null) {
            logRecord.setPayload(payloadString);
        }
        logRecord.setAction(action);
        // As AuthFilter checked the session (a session ended meanwhile is no session to ask), else as it was on arrival
        Object markers = httpRequest.getAttribute(ConsoleSession.MARKERS);
        logRecord.setImpersonatedBy(markers instanceof ConsoleSession.Markers
                ? ((ConsoleSession.Markers) markers).impersonatedBy : impersonatedBy);

        return logRecord;
    }

    /**
     * <p>Whether a response is Headwind's {@code {"status": "OK", ...}}. One that isn't JSON is not OK; the parse error
     * is dropped, never thrown out of the filter: its message quotes the response text, which can hold a secret (an
     * enrollment code), and the container would log it, with no audit record written.</p>
     */
    private static boolean isOk(byte[] content) {
        try {
            Response response = COMMAND_MAPPER.readValue(content, Response.class);
            return response != null && response.getStatus() == Response.ResponseStatus.OK;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * <p>The user the request ran as: the one in the security context (set by JWTFilter for an API client with a
     * bearer token, or by AuthFilter from the session; the auth filters run around this one for /rest/private/*),
     * else the one JWTFilter put on the request (when it ran inside this filter and has already released the
     * context), else the console session's. An API client has no session, so without the first two its actions
     * went to customer 1 with no login.</p>
     */
    static User actingUser(HttpServletRequest request) {
        SecurityContext context = SecurityContext.get();
        if (context != null && context.getCurrentUser().isPresent()) {
            return context.getCurrentUser().get();
        }
        Object tokenUser = request.getAttribute(JWTFilter.REQUEST_USER);
        if (tokenUser instanceof User) {
            return (User) tokenUser;
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object sessionUser = session.getAttribute(sessionCredentials);
            if (sessionUser instanceof User) {
                return (User) sessionUser;
            }
        }
        return null;
    }

    /** The body an agent command request is stored with ({@link AuditBody#COMMAND}). */
    static String stripCommandPayload(byte[] rawBody) {
        return AuditBody.COMMAND.stored(rawBody);
    }

    /**
     * <p>The login a body names, for a record the resource left none on ({@link UserCredentials#REQUEST_LOGIN}): the
     * login the resource's JSON binding would read from it, or none. It reads the body as Jackson does (a body Jackson
     * refuses anywhere, invalid UTF-8 included, names no login: the resource refused it too, so nobody signed in) and
     * the top-level fields only: just the value of {@code login} is decoded, nothing else of the body is kept, the
     * last login counts, a scalar is a login, and an object or array as a login fails the binding, so it names
     * none.</p>
     *
     * @param rawBody a request body as the client sent it.
     * @return the login, or {@code null} if the body names none or can't be read in full.
     */
    private static String loginOf(byte[] rawBody) {
        try (JsonParser parser = COMMAND_MAPPER.getFactory().createParser(rawBody)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            String login = null;
            boolean bindable = true;
            JsonToken token;
            while ((token = parser.nextToken()) == JsonToken.FIELD_NAME) {
                boolean isLogin = "login".equals(parser.currentName());
                JsonToken value = parser.nextToken();
                if (isLogin) {
                    bindable &= value.isScalarValue();
                    login = value.isScalarValue() && value != JsonToken.VALUE_NULL ? parser.getText() : null;
                } else if (value == JsonToken.VALUE_STRING) {
                    parser.getText();               // read as the binding reads it: an undecodable value refuses it
                }
                parser.skipChildren();
            }
            return token == JsonToken.END_OBJECT && bindable ? login : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
