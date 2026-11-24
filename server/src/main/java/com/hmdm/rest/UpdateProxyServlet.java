/*
 * MDMesh: same-origin gate in front of the updater/recovery supervisor.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest;

import com.hmdm.security.ConsoleSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <p>Passes the supervisor routes the console and devices use to the supervisor process: the loopback one on a native
 * install, {@code supervisor:9000} on Docker ({@code docker/entrypoint.sh}). Only the routes in {@link #ROUTES} go
 * through, each with its methods, to its fixed upstream path; any other path is 404 and any other method 405, and
 * neither reaches the supervisor.</p>
 *
 * <p>Two are public: the agent APK (QR enrollment and rollouts fetch it with no credentials; {@code ?v=} digits only)
 * and the supervisor's health check. Every other {@code /update} path needs a console session: PrivateRestModule maps
 * AuthFilter and PrivateIPFilter on it, as on {@code /rest/private}, and this servlet refuses a gated route that
 * AuthFilter didn't pass, so a mapping that stopped matching fails closed. The actions also need the console's CSRF
 * header. The supervisor still decides who may update the deployment (it asks the server about the forwarded
 * session). The recovery token is never forwarded: it works only on the host.</p>
 */
public class UpdateProxyServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(UpdateProxyServlet.class);

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 60 * 1000;
    /** The supervisor may first download an agent it hasn't cached (bounded at 10 min) before it answers. */
    private static final int APK_READ_TIMEOUT_MS = 11 * 60 * 1000;

    /** Headers forwarded to the supervisor: the session, the CSRF header and content negotiation. */
    private static final String[] FORWARD_HEADERS = {"cookie", "content-type", "accept", "x-mdmesh-console"};

    /** The agent APK's only query: one agent version code (a rollout's URL), or none (the offered agent). */
    private static final Pattern APK_QUERY = Pattern.compile("v=[0-9]{1,9}");

    private enum Gate { PUBLIC, SESSION, CONSOLE_ACTION }

    private static final class Route {
        final String upstream;
        final Gate gate;
        final int readTimeoutMs;
        final List<String> methods;

        Route(String upstream, Gate gate, int readTimeoutMs, String... methods) {
            this.upstream = upstream;
            this.gate = gate;
            this.readTimeoutMs = readTimeoutMs;
            this.methods = Arrays.asList(methods);
        }
    }

    private static final Route AGENT_APK = new Route("/update/agent.apk", Gate.PUBLIC, APK_READ_TIMEOUT_MS, "GET", "HEAD");

    /** What may reach the supervisor: the request's path info below {@code /update} → its route. */
    private static final Map<String, Route> ROUTES;

    static {
        Map<String, Route> routes = new HashMap<>();
        routes.put("/agent.apk", AGENT_APK);
        routes.put("/healthz", new Route("/healthz", Gate.PUBLIC, READ_TIMEOUT_MS, "GET"));
        routes.put("/status", new Route("/update/status", Gate.SESSION, READ_TIMEOUT_MS, "GET"));
        for (String action : new String[]{"check", "apply", "auto", "rollback"}) {
            routes.put("/" + action, new Route("/update/" + action, Gate.CONSOLE_ACTION, READ_TIMEOUT_MS, "POST"));
        }
        ROUTES = Collections.unmodifiableMap(routes);
    }

    private String supervisorBase;

    @Override
    public void init() {
        String base = getServletContext().getInitParameter("supervisor.base");
        supervisorBase = (base == null || base.trim().isEmpty())
                ? "http://127.0.0.1:9000" : base.trim().replaceAll("/+$", "");
    }

    String supervisorBase() {
        return supervisorBase;
    }

    /** The read timeout of the route at {@code pathInfo}. */
    static int readTimeoutMs(String pathInfo) {
        return ROUTES.get(pathInfo).readTimeoutMs;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Route route = req.getPathInfo() == null ? null : ROUTES.get(req.getPathInfo());
        if (route == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!route.methods.contains(req.getMethod())) {
            resp.setHeader("Allow", String.join(", ", route.methods));
            resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        if (route.gate != Gate.PUBLIC && req.getAttribute(ConsoleSession.MARKERS) == null) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (route.gate == Gate.CONSOLE_ACTION && !"1".equals(req.getHeader("X-MDMesh-Console"))) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String query = "";
        if (route == AGENT_APK && req.getQueryString() != null && !req.getQueryString().isEmpty()) {
            if (!APK_QUERY.matcher(req.getQueryString()).matches()) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }
            query = "?" + req.getQueryString();
        }
        forward(req, resp, route, query);
    }

    private void forward(HttpServletRequest req, HttpServletResponse resp, Route route, String query)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(supervisorBase + route.upstream + query).openConnection();
        try {
            conn.setRequestMethod(req.getMethod());
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(route.readTimeoutMs);
            conn.setInstanceFollowRedirects(false);
            for (String h : FORWARD_HEADERS) {
                Enumeration<String> vals = req.getHeaders(h);
                while (vals != null && vals.hasMoreElements()) {
                    conn.addRequestProperty(h, vals.nextElement());
                }
            }
            if ("POST".equals(req.getMethod())) {
                conn.setDoOutput(true);
                try (InputStream in = req.getInputStream(); OutputStream out = conn.getOutputStream()) {
                    copy(in, out);
                }
            }

            int status = conn.getResponseCode();
            resp.setStatus(status);
            String ct = conn.getContentType();
            if (ct != null) {
                resp.setContentType(ct);
            }
            long len = conn.getContentLengthLong();
            if (len >= 0 && len <= Integer.MAX_VALUE) {
                resp.setContentLength((int) len);
            }
            InputStream body = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (body != null) {
                try (InputStream in = body; OutputStream out = resp.getOutputStream()) {
                    copy(in, out);
                }
            }
        } catch (IOException e) {
            // Supervisor down/absent. 503 + the console's envelope shape so callers fail cleanly
            // (the Settings page simply hides the Updates section when this fetch fails).
            logger.debug("Supervisor unreachable at {}: {}", supervisorBase, e.toString());
            resp.setStatus(503);
            resp.setContentType("application/json");
            resp.getOutputStream().write(
                    "{\"status\":\"ERROR\",\"message\":\"updater supervisor unreachable\"}".getBytes());
        } finally {
            conn.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
    }
}
