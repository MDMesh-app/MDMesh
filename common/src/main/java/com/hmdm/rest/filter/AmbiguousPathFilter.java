/*
 * MDMesh: refuses request paths that the routing layers would read differently from the security filters.
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
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * <p>Answers 400 for any request whose raw path (below the context path, without the query string) has a spelling
 * that the layers which route a request would read differently from the layers which guard it.</p>
 *
 * <p>The Guice servlet filters ({@code JWTFilter}, {@code AuthFilter}, {@code PrivateIPFilter} and the plugins' own)
 * match their patterns against the raw request URI. Tomcat maps the request, and Jersey picks the resource, after
 * dropping {@code ;name=value} path parameters (and Tomcat also after decoding and normalising the path). So
 * {@code /rest/private;x=y/users/current} reaches the resource while {@code /rest/private/*} does not match it. Rather
 * than teach every filter every spelling, the spellings themselves are refused:</p>
 * <ul>
 *   <li>a {@code ;} anywhere in the path (path parameters, {@code ;jsessionid=} URL rewriting included);</li>
 *   <li>a backslash, or an encoded {@code ;}, {@code /}, {@code \}, {@code .} or NUL ({@code %3B}, {@code %2F},
 *       {@code %5C}, {@code %2E}, {@code %00}, either case);</li>
 *   <li>an empty segment ({@code //}), or a {@code .} or {@code ..} segment.</li>
 * </ul>
 * <p>No client of the server sends any of these: the console and the agent build plain paths, hosted file names have
 * no {@code ;} or {@code %} (uploads replace them), and the query string, which is not part of the path, is left
 * alone. Other percent-escapes ({@code %20}, {@code %2B}, UTF-8, {@code %25}) are passed on: they decode to ordinary
 * characters inside one segment, and nothing in the server decodes a path twice.</p>
 *
 * <p>Declared first in {@code web.xml}, for {@code /*}, ahead of the legacy device API filter and the Guice filter, so
 * no other filter, servlet or resource sees such a request. {@code PrivateRouteGuard} in the REST layer is the second
 * line: a private resource never runs without a signed-in user, whatever the path looked like.</p>
 */
public class AmbiguousPathFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest && !isUnambiguous(pathOf((HttpServletRequest) request))) {
            ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
    }

    /** The raw request URI below the context path. */
    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri != null && contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /**
     * True when {@code path} (a raw request path below the context path, no query string) means the same to Tomcat,
     * Jersey and the Guice filter patterns: no {@code ;}, no backslash, no encoded separator, dot or NUL, and no empty,
     * {@code .} or {@code ..} segment. A null path is refused.
     */
    static boolean isUnambiguous(String path) {
        if (path == null) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == ';' || c == '\\') {
                return false;
            }
            if (c == '%' && i + 2 < path.length()) {
                int hi = Character.digit(path.charAt(i + 1), 16);
                int lo = Character.digit(path.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    int b = (hi << 4) + lo;
                    if (b == ';' || b == '/' || b == '\\' || b == '.' || b == 0) {
                        return false;
                    }
                }
            }
        }
        if (path.contains("//")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
