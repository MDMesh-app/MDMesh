/*
 * MDMesh: a private REST route never runs without a signed-in user.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest.filter;

import com.hmdm.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

/**
 * <p>Refuses (403, as the auth filters do) a request to a private REST route when no signed-in user is set up for it.
 * A route is private when any segment of its path, path parameters dropped and decoded, is {@code private}: the
 * console's {@code /rest/private/*}, {@code /rest/plugin/main/private/*}, {@code /rest/notification/private/*} and every
 * plugin's {@code .../private...} routes.</p>
 *
 * <p>The servlet filters ({@code JWTFilter}, {@code AuthFilter}) set the user up for these routes, but they are matched
 * by URL pattern before the request is routed. This check runs after Jersey has matched a resource, on the path Jersey
 * routed by, so a request that reached a private resource without passing them is stopped here instead of running
 * with no user (which used to end in a NullPointerException, or worse, in a handler that never looks at the user).
 * {@link AmbiguousPathFilter} is the first line; this is the second.</p>
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class PrivateRouteGuard implements ContainerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(PrivateRouteGuard.class);

    @Override
    public void filter(ContainerRequestContext request) {
        if (isPrivate(request) && !signedIn()) {
            logger.debug("Refused {} {}: a private route with no signed-in user", request.getMethod(),
                    request.getUriInfo().getPath(false));
            request.abortWith(Response.status(Response.Status.FORBIDDEN).build());
        }
    }

    /** True when any segment of the routed path (decoded, without its path parameters) is {@code private}. */
    static boolean isPrivate(ContainerRequestContext request) {
        for (PathSegment segment : request.getUriInfo().getPathSegments(true)) {
            if ("private".equalsIgnoreCase(segment.getPath().trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean signedIn() {
        SecurityContext context = SecurityContext.get();
        return context != null && context.getCurrentUser().isPresent();
    }
}
