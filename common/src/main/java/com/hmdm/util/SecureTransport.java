/*
 * MDMesh: what the install's public address says about HTTPS (Secure session cookie, HSTS).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.util;

import javax.servlet.ServletContext;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * <p>Whether the install is served over HTTPS, decided by its public address, the {@code base.url} context parameter
 * (BASE_URL), the one the console, the QR code and every device use.</p>
 *
 * <p>TLS ends in front of Tomcat (Caddy on Docker, the operator's proxy on native installs), so Tomcat sees plain HTTP
 * on every request and cannot tell by itself. A forwarded scheme header would depend on each proxy sending it and on
 * which peers to believe; the base URL is known on every install, the same for every request.</p>
 */
public final class SecureTransport {

    /** The context parameter holding the install's public address. */
    public static final String BASE_URL_PARAMETER = "base.url";

    /** Strict-Transport-Security for an HTTPS install: six months, this host only, no preload. */
    public static final String HSTS = "max-age=15552000";

    private SecureTransport() {
    }

    /** True when {@code baseUrl} is an {@code https://} address (any letter case, surrounding blanks ignored). */
    public static boolean isHttps(String baseUrl) {
        return baseUrl != null && baseUrl.trim().toLowerCase(Locale.ROOT).startsWith("https://");
    }

    /** True when the context's {@code base.url} is an {@code https://} address. */
    public static boolean isHttps(ServletContext context) {
        return context != null && isHttps(context.getInitParameter(BASE_URL_PARAMETER));
    }

    /**
     * <p>Marks the session cookie (JSESSIONID) {@code Secure} when the install is served over HTTPS, so a browser
     * never sends it over plain HTTP. An HTTP install (a LAN address) keeps a cookie that works over HTTP. Tomcat
     * already makes it {@code HttpOnly}; the Context's cookie processor makes it {@code SameSite=Lax}.</p>
     *
     * <p>Must run while the context starts (a {@code ServletContextListener}). The servlet API the server compiles
     * against predates {@code SessionCookieConfig} (Servlet 3.0), so it is reached by reflection; Tomcat 9 has it.</p>
     *
     * @return true when the cookie was marked Secure
     * @throws IllegalStateException when the install is HTTPS but the cookie could not be marked Secure. The
     *                               Initializer logs it and starts anyway, with a cookie that isn't Secure: a
     *                               reachable console beats one that won't start over a cookie flag
     */
    public static boolean secureSessionCookie(ServletContext context) {
        if (!isHttps(context)) {
            return false;
        }
        try {
            Method getConfig = ServletContext.class.getMethod("getSessionCookieConfig");
            Object config = getConfig.invoke(context);
            Class<?> type = Class.forName("javax.servlet.SessionCookieConfig", false, ServletContext.class.getClassLoader());
            type.getMethod("setSecure", boolean.class).invoke(config, true);
            return Boolean.TRUE.equals(type.getMethod("isSecure").invoke(config));
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Could not mark the session cookie Secure for the https base.url", e);
        }
    }
}
