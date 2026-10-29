/*
 * MDMesh: browser security headers on every response the server sends.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest.filter;

import com.hmdm.util.SecureTransport;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * <p>Sets the browser security headers on every response: no MIME sniffing, no framing (nothing the server serves is
 * meant to be framed: the console, its QR view and the recovery page are top-level pages, and the agent has no
 * WebView), no Referer to other sites, and, when the install's base URL is https, Strict-Transport-Security.</p>
 *
 * <p>On Docker the edge (docker/Caddyfile) sets the same headers, so this matters on native installs, where Tomcat
 * serves the console itself behind the operator's own proxy. HSTS follows the base URL rather than the request: TLS
 * ends at the proxy, and a browser ignores HSTS that reaches it over plain HTTP (a LAN address of an https install).
 * Declared in {@code web.xml} right after {@link AmbiguousPathFilter}.</p>
 */
public class SecurityHeadersFilter implements Filter {

    private volatile boolean https;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.https = SecureTransport.isHttps(filterConfig.getServletContext());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse) {
            apply((HttpServletResponse) response, https);
        }
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
    }

    /** Sets the headers on {@code response}; HSTS only when {@code https}. */
    static void apply(HttpServletResponse response, boolean https) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        response.setHeader("Referrer-Policy", "same-origin");
        if (https) {
            response.setHeader("Strict-Transport-Security", SecureTransport.HSTS);
        }
    }
}
