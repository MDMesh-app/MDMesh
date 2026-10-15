/*
 * MDMesh: turns the Headwind launcher device routes off unless legacy.device.api is set.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest.filter;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * <p>Answers 404 for the device routes of the Headwind launcher (and the unauthenticated Headwind usage-stats and
 * launcher provisioning QR routes, which MDMesh has no use for: the console draws the agent's QR itself) unless the
 * {@code legacy.device.api} context parameter
 * is {@code 1} or {@code true}. The MDMesh agent speaks only {@code /rest/public/agent/v1/*} and the wake socket, and
 * the console reads device data through private routes, so none of these is needed by default.</p>
 *
 * <p>Declared in {@code web.xml} ahead of the Guice filter, so it sees every request before any REST resource or
 * servlet does. The path is checked twice: as the container decoded and normalised it (servlet path + path info) and
 * as the raw request URI with path parameters dropped, percent-escapes decoded, repeated slashes collapsed and case
 * folded, so no spelling the REST layer would still route gets past it.</p>
 */
public class LegacyDeviceApiFilter implements Filter {

    /** The context parameter (ROOT.xml) that turns the legacy device routes on. */
    public static final String PARAMETER = "legacy.device.api";

    /** Path prefixes (below the context path, lower case) of the legacy device routes. */
    static final List<String> LEGACY_PREFIXES = Collections.unmodifiableList(Arrays.asList(
            "/rest/public/sync",
            "/rest/notifications/device",
            "/rest/notification/polling",
            "/rest/plugins/deviceinfo/deviceinfo/public",
            "/rest/plugins/deviceinfo/deviceinfo-plugin-settings/device",
            "/rest/plugins/devicelog/log/list",
            "/rest/plugins/devicelog/log/rules",
            "/rest/plugins/messaging/public",
            "/rest/public/stats",
            "/rest/public/qr"
    ));

    private volatile boolean enabled;

    /** True when {@code value} turns the legacy device API on: {@code 1} or {@code true}, any case. */
    public static boolean isEnabled(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return v.equals("1") || v.equalsIgnoreCase("true");
    }

    /** True when the context's {@code legacy.device.api} parameter turns the legacy device API on. */
    public static boolean isEnabled(ServletContext context) {
        return context != null && isEnabled(context.getInitParameter(PARAMETER));
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.enabled = isEnabled(filterConfig.getServletContext());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!enabled && request instanceof HttpServletRequest && isLegacy((HttpServletRequest) request)) {
            ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
    }

    static boolean isLegacy(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String decoded = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        if (matches(canonical(decoded))) {
            return true;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return matches(canonical(percentDecode(stripPathParameters(uri))));
    }

    /** True when {@code path} (below the context path) is one of the legacy routes, however it is spelled. */
    static boolean isLegacyPath(String path) {
        return matches(canonical(percentDecode(stripPathParameters(path))));
    }

    private static boolean matches(String path) {
        for (String prefix : LEGACY_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Lower case, one leading slash, no repeated slashes. */
    private static String canonical(String path) {
        String p = path.replace('\\', '/').replaceAll("/{2,}", "/").toLowerCase(Locale.ROOT);
        return p.startsWith("/") ? p : "/" + p;
    }

    /** Drops {@code ;name=value} path parameters from every segment (the REST layer reads them as matrix params). */
    private static String stripPathParameters(String uri) {
        StringBuilder out = new StringBuilder(uri.length());
        boolean inParams = false;
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c == ';') {
                inParams = true;
            } else if (c == '/') {
                inParams = false;
                out.append(c);
            } else if (!inParams) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Decodes {@code %XX} escapes as UTF-8 (a {@code +} stays a {@code +}: it is not a space in a path). */
    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()
                    && Character.digit(s.charAt(i + 1), 16) >= 0 && Character.digit(s.charAt(i + 2), 16) >= 0) {
                bytes.write((Character.digit(s.charAt(i + 1), 16) << 4) + Character.digit(s.charAt(i + 2), 16));
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(b, 0, b.length);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
