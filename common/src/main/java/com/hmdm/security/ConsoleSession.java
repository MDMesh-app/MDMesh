/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.security;

import com.hmdm.persistence.domain.User;
import com.hmdm.rest.filter.AuthFilter;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opens the console session for a user. Every sign-in, user switch (impersonation) and password change goes through
 * here, so a session id the browser held before is never the id of a signed-in session: {@link #start} ends any
 * session the request came with and opens a new one; {@link #renew} moves a signed-in session to a new id.
 *
 * <p>The servlet API this builds against (2.5) has no {@code changeSessionId}, so both end the old session and open
 * a new one, which every container supports. ConsoleSessionTest checks that no other server code opens a session or
 * stores a user in one.</p>
 */
public final class ConsoleSession {

    private ConsoleSession() {
    }

    /**
     * Ends the request's session, if any, and opens a new one holding only {@code user}. Call it only once the user
     * is authenticated (or the switch allowed): a refused attempt must leave the session alone.
     */
    public static HttpSession start(HttpServletRequest req, User user) {
        end(req.getSession(false));
        HttpSession session = req.getSession(true);
        session.setAttribute(AuthFilter.sessionCredentials, user);
        session.setAttribute(FORMAT, CURRENT_FORMAT);
        return session;
    }

    /**
     * The session attribute every session {@link #start} opens carries. A session holding a user without it was opened
     * by a release before 0.5.1 (Tomcat keeps sessions across a restart on native installs) and may be one signed in as
     * another account that was never marked: AuthFilter refuses it, so every such session ends once.
     */
    static final String FORMAT = "consoleSessionFormat";
    static final Integer CURRENT_FORMAT = 1;

    /** A console session's markers, read at once from the one session object the request's user came from. */
    public static final class Markers {
        /** A request not run from a console session (an API client's JWT): never signed in as another account. */
        public static final Markers NONE = new Markers(true, null, null);

        /** Opened by this release ({@link #FORMAT}). */
        public final boolean current;
        /** Opened by signing in as another account ({@link #startImpersonated}). */
        public final boolean impersonated;
        /** The account that signed in, and its authToken then; null when not impersonated, or missing. */
        public final Integer impersonatedBy;
        public final String impersonatorToken;

        private Markers(boolean current, Object by, Object token) {
            this.current = current;
            this.impersonated = by != null;
            this.impersonatedBy = by instanceof Integer ? (Integer) by : null;
            this.impersonatorToken = token instanceof String && !((String) token).isEmpty() ? (String) token : null;
        }
    }

    /**
     * The markers of {@code session}.
     *
     * @throws IllegalStateException if the session has ended: the caller refuses the request, it never reads that as
     *         "not marked".
     */
    public static Markers markers(HttpSession session) {
        return new Markers(CURRENT_FORMAT.equals(session.getAttribute(FORMAT)), session.getAttribute(IMPERSONATED_BY),
                session.getAttribute(IMPERSONATOR_TOKEN));
    }

    /** Whether {@code session} has not ended (another request may have ended it meanwhile). */
    public static boolean isLive(HttpSession session) {
        try {
            session.getAttribute(FORMAT);
            return true;
        } catch (IllegalStateException ended) {
            return false;
        }
    }

    /** Ends {@code session}, if it has not ended already. */
    public static void endSession(HttpSession session) {
        end(session);
    }

    /** The session attribute naming the account that signed in as another one (the session's user): its id. */
    static final String IMPERSONATED_BY = "impersonatedBy";

    /**
     * The session attribute holding the authToken the account that signed in as another one had then: a password
     * change (a new token) or a deletion ends the sessions it opened as others ({@link AuthFilter}).
     */
    static final String IMPERSONATOR_TOKEN = "impersonatorToken";

    /**
     * As {@link #start}, for an administrator signing in as another account ({@code by} as {@code user}). The session
     * is marked: it never knows {@code user}'s password, so what asks for it is refused outright
     * ({@link #isImpersonated}), rather than counting failures against {@code user}'s sign-in limit.
     */
    public static HttpSession startImpersonated(HttpServletRequest req, User user, User by) {
        HttpSession session = start(req, user);
        session.setAttribute(IMPERSONATED_BY, by == null || by.getId() == null ? Integer.valueOf(0) : by.getId());
        if (by != null && by.getAuthToken() != null && !by.getAuthToken().isEmpty()) {
            session.setAttribute(IMPERSONATOR_TOKEN, by.getAuthToken());
        }
        return session;
    }

    /**
     * The request attribute AuthFilter puts its {@link Markers} on: read from the session the request's user came from,
     * so what the resource asks below is answered as AuthFilter checked it, even when the session ends meanwhile.
     */
    public static final String MARKERS = ConsoleSession.class.getName() + ".markers";

    /** Marks a request AuthFilter let through: with the session's markers, or {@link Markers#NONE} (an API client). */
    public static void mark(ServletRequest req, Markers markers) {
        req.setAttribute(MARKERS, markers);
    }

    /** The id of the account that signed in as the session's user ({@link #startImpersonated}), or null. */
    public static Integer impersonatorOf(HttpServletRequest req) {
        Markers markers = markersOf(req);
        if (markers != null) {
            return markers.impersonatedBy;
        }
        HttpSession session = req == null ? null : req.getSession(false);
        try {
            Object id = session == null ? null : session.getAttribute(IMPERSONATED_BY);
            return id instanceof Integer ? (Integer) id : null;
        } catch (IllegalStateException endedMeanwhile) {
            return null;
        }
    }

    /**
     * Whether the request's console session was opened by signing in as another account ({@link #startImpersonated}):
     * as AuthFilter read it ({@link #MARKERS}), or else from the session. A request that came with a session that has
     * ended can't vouch it wasn't, so it counts as one: what this guards (a password check, signing in as someone
     * else) is refused.
     */
    public static boolean isImpersonated(HttpServletRequest req) {
        Markers markers = markersOf(req);
        if (markers != null) {
            return markers.impersonated;
        }
        if (req == null) {
            return false;
        }
        HttpSession session = req.getSession(false);
        if (session == null) {
            return req.getRequestedSessionId() != null;
        }
        try {
            return session.getAttribute(IMPERSONATED_BY) != null;
        } catch (IllegalStateException endedMeanwhile) {
            return true;
        }
    }

    private static Markers markersOf(HttpServletRequest req) {
        Object markers = req == null ? null : req.getAttribute(MARKERS);
        return markers instanceof Markers ? (Markers) markers : null;
    }

    /**
     * Moves the request's session, with all its attributes, to a new id; null if the request has no live session
     * (none is opened then).
     */
    public static HttpSession renew(HttpServletRequest req) {
        HttpSession old = req.getSession(false);
        if (old == null) {
            return null;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        try {
            Enumeration<?> names = old.getAttributeNames();
            while (names != null && names.hasMoreElements()) {
                String name = (String) names.nextElement();
                attributes.put(name, old.getAttribute(name));
            }
        } catch (IllegalStateException endedMeanwhile) {
            return null;
        }
        end(old);
        HttpSession session = req.getSession(true);
        attributes.forEach(session::setAttribute);
        return session;
    }

    private static void end(HttpSession session) {
        if (session == null) {
            return;
        }
        try {
            session.invalidate();
        } catch (IllegalStateException alreadyEnded) {
            // It timed out, or another request ended it: there is nothing left to end.
        }
    }
}
