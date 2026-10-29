/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.model.ResourceMethod;
import org.junit.Test;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Impersonating switches the session to another user, so it is a POST: the SameSite=Lax session cookie goes along
 * with a cross-site link (a top-level GET), not with a cross-site POST. A GET finds no method (405).
 * Who may impersonate whom is tested on the methods themselves (UserResourceTest, CustomerResourceTest).
 */
public class ImpersonationIsAPostTest {

    @Test
    public void userImpersonationAnswersOnlyAPost() throws Exception {
        assertOnlyPost(UserResource.class, "impersonate/{id}");
        assertEquals(405, status(new UserResource(), "GET", "/private/users/impersonate/5"));
    }

    @Test
    public void customerImpersonationAnswersOnlyAPost() throws Exception {
        assertOnlyPost(CustomerResource.class, "impersonate/{id}");
        assertEquals(405, status(new CustomerResource(), "GET", "/private/customers/impersonate/5"));
    }

    private static void assertOnlyPost(Class<?> resourceClass, String path) {
        Resource model = Resource.from(resourceClass);
        Resource child = model.getChildResources().stream()
                .filter(r -> r.getPath().replaceAll("^/|/$", "").equals(path))
                .findFirst().orElse(null);
        assertNotNull(path + " in " + model.getChildResources(), child);
        List<String> methods = child.getResourceMethods().stream().map(ResourceMethod::getHttpMethod)
                .collect(Collectors.toList());
        assertEquals(path, java.util.Collections.singletonList("POST"), methods);
    }

    /** Routes one request through Jersey in memory, as the servlet would; the resource is never called for a 405. */
    private static int status(Object resource, String method, String path) throws Exception {
        ApplicationHandler app = new ApplicationHandler(new ResourceConfig().register(resource));
        ContainerRequest request = new ContainerRequest(URI.create("http://localhost/rest/"),
                URI.create("http://localhost/rest" + path), method, null, new org.glassfish.jersey.internal.MapPropertiesDelegate());
        ContainerResponse response = app.apply(request).get();
        return response.getStatus();
    }
}
