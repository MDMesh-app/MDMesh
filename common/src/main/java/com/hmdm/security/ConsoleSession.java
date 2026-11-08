/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.security;

import com.hmdm.persistence.domain.User;
import com.hmdm.rest.filter.AuthFilter;

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
        return session;
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
