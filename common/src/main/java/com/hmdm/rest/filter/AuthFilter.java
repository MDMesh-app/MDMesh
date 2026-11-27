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

package com.hmdm.rest.filter;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.security.ConsoleSession;
import com.hmdm.security.SecurityContext;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@Singleton
public class AuthFilter implements Filter {

    public static final String sessionCredentials = "credentials";

    private UserDAO userDAO;

    public AuthFilter() {
    }

    @Inject
    public AuthFilter(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    public void init(FilterConfig filterConfig) throws ServletException {
    }

    public void destroy() {
    }

    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        // If security context is already established then let the request to continue without check
        if (SecurityContext.get() != null && SecurityContext.get().getCurrentUser().isPresent()) {
            User user = SecurityContext.get().getCurrentUser().get();
            if (user.isPasswordReset()) {
                ((HttpServletResponse)servletResponse).sendError(403);
                return;
            }
            // An API client's request (JWTFilter): whatever session cookie it also sends, it runs as the token's user.
            // Markers already on the request (AuthFilter met it before) are what was checked: kept.
            if (servletRequest.getAttribute(ConsoleSession.MARKERS) == null) {
                ConsoleSession.mark(servletRequest, ConsoleSession.Markers.NONE);
            }
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        // Check that the user is authenticated and forbid access to app if not
        User currentUser = null;
        // The one session object the user, and its markers, are read from: never looked up again for this request
        HttpSession session = null;
        ConsoleSession.Markers markers = null;
        if (servletRequest instanceof HttpServletRequest && servletResponse instanceof HttpServletResponse) {
            session = ((HttpServletRequest)servletRequest).getSession(false);
            try {
                currentUser = session == null ? null : (User) session.getAttribute(sessionCredentials);
                markers = currentUser == null ? null : ConsoleSession.markers(session);
            } catch (IllegalStateException endedMeanwhile) {
                currentUser = null;
            }
            if (currentUser == null) {
                ((HttpServletResponse)servletResponse).sendError(403);
                return;
            } else if (!markers.current) {
                // Opened before this release and kept across the update: sign in again, once
                ConsoleSession.endSession(session);
                ((HttpServletResponse)servletResponse).sendError(403);
                return;
            }
        }

        // Set-up the security context
        try {
            SecurityContext.init(currentUser);
            User dbUser = userDAO.getUserDetails(currentUser.getId());
            if (dbUser.isPasswordReset() || dbUser.getAuthToken() == null || currentUser.getAuthToken() == null ||
                    !currentUser.getAuthToken().equals(dbUser.getAuthToken())) {
                ((HttpServletResponse)servletResponse).sendError(403);
                return;
            }
            // A session opened by signing in as another account goes on only while that admin still may, and only while
            // it lasts: another request may have ended it (failing this check) since its user was read above
            if (markers.impersonated && (!userDAO.mayStillImpersonate(markers.impersonatedBy,
                    markers.impersonatorToken, dbUser) || !ConsoleSession.isLive(session))) {
                ConsoleSession.endSession(session);
                ((HttpServletResponse)servletResponse).sendError(403);
                return;
            }
            // Avoid cookie-based penetration
            // Changing user data in cookies may be used, for example, to elevate user's permissions to admin
            SecurityContext.release();
            SecurityContext.init(dbUser);
            // What the resource asks about this session is answered as checked here (ConsoleSession.isImpersonated)
            ConsoleSession.mark(servletRequest, markers);
            filterChain.doFilter(servletRequest, servletResponse);
        } finally {
            SecurityContext.release();
        }
    }
}
