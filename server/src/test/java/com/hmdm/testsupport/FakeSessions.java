package com.hmdm.testsupport;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Servlet sessions that behave like a container's: each has its own id, an invalidated one refuses its attributes
 * (IllegalStateException, as Tomcat does) and {@code getSession(true)} after an invalidate opens a new one.
 */
public final class FakeSessions {

    private int nextId = 1;
    private Session current;
    /** Every session this request opened or came with, in order. */
    public final List<Session> all = new ArrayList<>();
    /** How many times the request was asked to open a session it didn't have. */
    public int created;

    /** One fake session. */
    public final class Session {
        public final String id;
        public final Map<String, Object> attributes = new LinkedHashMap<>();
        public boolean invalidated;
        public final HttpSession proxy;

        Session(String id) {
            this.id = id;
            this.proxy = (HttpSession) Proxy.newProxyInstance(FakeSessions.class.getClassLoader(),
                    new Class<?>[]{HttpSession.class}, (p, method, args) -> {
                        String name = method.getName();
                        if (name.equals("getId")) {
                            return this.id;
                        }
                        if (name.equals("hashCode")) {
                            return System.identityHashCode(p);
                        }
                        if (name.equals("equals")) {
                            return p == args[0];
                        }
                        if (name.equals("toString")) {
                            return "session " + this.id;
                        }
                        if (invalidated) {
                            throw new IllegalStateException("session " + this.id + " is invalidated");
                        }
                        switch (name) {
                            case "getAttribute":
                                return attributes.get((String) args[0]);
                            case "setAttribute":
                                if (args[1] == null) {
                                    attributes.remove((String) args[0]);
                                } else {
                                    attributes.put((String) args[0], args[1]);
                                }
                                return null;
                            case "removeAttribute":
                                attributes.remove((String) args[0]);
                                return null;
                            case "getAttributeNames":
                                return Collections.enumeration(new ArrayList<>(attributes.keySet()));
                            case "invalidate":
                                invalidated = true;
                                if (current == this) {
                                    current = null;
                                }
                                return null;
                            case "getMaxInactiveInterval":
                                return 0;
                            default:
                                return null;
                        }
                    });
        }
    }

    /** A request with no session yet. */
    public FakeSessions() {
    }

    /** The request already has a session (one a browser was given before signing in). */
    public Session existing(String id) {
        current = new Session(id);
        all.add(current);
        return current;
    }

    /** The session the request has now, or null. */
    public Session current() {
        return current == null || current.invalidated ? null : current;
    }

    /** Answers {@code getSession()} / {@code getSession(boolean)} for a request proxy; null for other methods. */
    public HttpSession getSession(Object[] args) {
        boolean create = args == null || args.length == 0 || (Boolean) args[0];
        if (current != null && current.invalidated) {
            current = null;   // as a container does: an ended session is no session
        }
        if (current == null && create) {
            current = new Session("fake-" + nextId++);
            all.add(current);
            created++;
        }
        return current == null ? null : current.proxy;
    }

    /** A request that has only these sessions (every other method answers null). */
    public HttpServletRequest request() {
        return (HttpServletRequest) Proxy.newProxyInstance(FakeSessions.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> "getSession".equals(method.getName()) ? getSession(args) : null);
    }
}
