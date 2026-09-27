/*
 * MDMesh: same-origin passthrough to the updater/recovery supervisor.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;

/**
 * <p>Streams {@code /update/*} to the loopback supervisor process, so the console's Updates and
 * rollout views are same-origin on deployments with no fronting router of their own (the native
 * install: any TLS proxy → Tomcat → this servlet → 127.0.0.1:9000). On the Docker stack Caddy
 * routes {@code /update/*} straight to the supervisor and this servlet is simply never hit.</p>
 *
 * <p>Deliberately dumb: no caching, no rewriting, fixed loopback default. The supervisor performs
 * its own authorization (session-cookie forward + CSRF header + recovery token), so the servlet
 * forwards those verbatim and adds nothing.</p>
 */
public class UpdateProxyServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(UpdateProxyServlet.class);

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 60000; // APK mirror downloads stream through here

    /** Headers forwarded to the supervisor — auth/CSRF material plus content negotiation. */
    private static final String[] FORWARD_HEADERS = {
            "cookie", "content-type", "accept", "x-mdmesh-console", "x-recovery-token",
    };

    private String supervisorBase;

    @Override
    public void init() {
        String base = getServletContext().getInitParameter("supervisor.base");
        supervisorBase = (base == null || base.trim().isEmpty())
                ? "http://127.0.0.1:9000" : base.trim().replaceAll("/+$", "");
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getRequestURI();
        String query = req.getQueryString();
        URL target = new URL(supervisorBase + path + (query == null ? "" : "?" + query));

        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        try {
            conn.setRequestMethod(req.getMethod());
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            for (String h : FORWARD_HEADERS) {
                Enumeration<String> vals = req.getHeaders(h);
                while (vals != null && vals.hasMoreElements()) {
                    conn.addRequestProperty(h, vals.nextElement());
                }
            }
            if ("POST".equalsIgnoreCase(req.getMethod()) || "PUT".equalsIgnoreCase(req.getMethod())) {
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
