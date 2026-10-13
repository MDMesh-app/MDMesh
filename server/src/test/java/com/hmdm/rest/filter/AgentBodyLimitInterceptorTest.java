/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.filter;

import com.hmdm.rest.resource.AgentResource;
import org.junit.Test;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.ReaderInterceptorContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/** The agent API reads at most a fixed number of request-body bytes per request. */
public class AgentBodyLimitInterceptorTest {

    private static final int LIMIT = 1024;

    /** A context whose proceed() reads the whole (possibly wrapped) entity stream, like the JSON reader does. */
    private static ReaderInterceptorContext context(String contentLength, byte[] body, AtomicBoolean proceeded) {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        if (contentLength != null) {
            headers.putSingle("Content-Length", contentLength);
        }
        AtomicReference<InputStream> stream = new AtomicReference<>(new ByteArrayInputStream(body));
        return (ReaderInterceptorContext) Proxy.newProxyInstance(AgentBodyLimitInterceptorTest.class.getClassLoader(),
                new Class<?>[]{ReaderInterceptorContext.class}, (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "getHeaders": return headers;
                        case "getInputStream": return stream.get();
                        case "setInputStream": stream.set((InputStream) args[0]); return null;
                        case "proceed":
                            proceeded.set(true);
                            return drain(stream.get());
                        default: return null;
                    }
                });
    }

    private static int drain(InputStream in) throws IOException {
        int n = 0;
        byte[] buf = new byte[100];
        int r;
        while ((r = in.read(buf)) != -1) n += r;
        return n;
    }

    @Test
    public void defaultLimitIsFarAboveRealAgentRequests() {
        // Far above anything a real agent sends, so a legitimate device is never locked out by a resend loop.
        assertEquals(32L * 1024 * 1024, AgentBodyLimitInterceptor.MAX_BODY_BYTES);
    }

    @Test
    public void appliesToTheAgentResource() {
        assertNotNull(AgentResource.class.getAnnotation(AgentBodyLimit.class));
        assertNotNull(AgentBodyLimitInterceptor.class.getAnnotation(AgentBodyLimit.class));
        // HMDMApplication registers providers by scanning com.hmdm.
        org.junit.Assert.assertTrue(new org.glassfish.jersey.server.ResourceConfig().packages("com.hmdm")
                .getClasses().contains(AgentBodyLimitInterceptor.class));
    }

    @Test
    public void rejectsADeclaredOversizedBodyBeforeReadingIt() throws Exception {
        AtomicBoolean proceeded = new AtomicBoolean();
        try {
            new AgentBodyLimitInterceptor(LIMIT).aroundReadFrom(
                    context(String.valueOf(LIMIT + 1), new byte[LIMIT + 1], proceeded));
            fail("expected 413");
        } catch (WebApplicationException e) {
            assertEquals(413, e.getResponse().getStatus());
        }
        assertFalse(proceeded.get());
    }

    @Test
    public void stopsAnUndeclaredOversizedBodyWhileReading() throws Exception {
        try {
            new AgentBodyLimitInterceptor(LIMIT).aroundReadFrom(context(null, new byte[LIMIT * 4], new AtomicBoolean()));
            fail("reading past the limit must fail");
        } catch (WebApplicationException e) {
            assertEquals(413, e.getResponse().getStatus());
        }
    }

    @Test
    public void anUnderstatedContentLengthIsStillBounded() throws Exception {
        try {
            new AgentBodyLimitInterceptor(LIMIT).aroundReadFrom(context("10", new byte[LIMIT * 4], new AtomicBoolean()));
            fail("reading past the limit must fail");
        } catch (WebApplicationException e) {
            assertEquals(413, e.getResponse().getStatus());
        }
    }

    @Test
    public void passesABodyAtTheLimit() throws Exception {
        Object read = new AgentBodyLimitInterceptor(LIMIT).aroundReadFrom(
                context(String.valueOf(LIMIT), new byte[LIMIT], new AtomicBoolean()));
        assertEquals(LIMIT, read);
    }

    // ---- wired through Jersey, the way HMDMApplication registers providers -------------------------------------------

    @javax.ws.rs.Path("/limited")
    @AgentBodyLimit
    public static class LimitedResource {
        @javax.ws.rs.POST
        @javax.ws.rs.Consumes("text/plain")
        public String echo(String body) { return String.valueOf(body.length()); }
    }

    @javax.ws.rs.Path("/open")
    public static class OpenResource {
        @javax.ws.rs.POST
        @javax.ws.rs.Consumes("text/plain")
        public String echo(String body) { return String.valueOf(body.length()); }
    }

    private static int post(org.glassfish.jersey.server.ApplicationHandler app, String path, int bytes) throws Exception {
        org.glassfish.jersey.server.ContainerRequest req = new org.glassfish.jersey.server.ContainerRequest(
                java.net.URI.create("http://localhost/"), java.net.URI.create("http://localhost" + path), "POST",
                null, new org.glassfish.jersey.internal.MapPropertiesDelegate());
        req.header("Content-Type", "text/plain");
        req.setEntityStream(new ByteArrayInputStream(new byte[bytes]));
        return app.apply(req).get().getStatus();
    }

    @Test
    public void jerseyAppliesTheLimitOnlyToAnnotatedResources() throws Exception {
        org.glassfish.jersey.server.ApplicationHandler app = new org.glassfish.jersey.server.ApplicationHandler(
                new org.glassfish.jersey.server.ResourceConfig(LimitedResource.class, OpenResource.class,
                        AgentBodyLimitInterceptor.class));
        int big = (int) AgentBodyLimitInterceptor.MAX_BODY_BYTES + 1;
        assertEquals(200, post(app, "/limited", 1000));
        assertEquals(413, post(app, "/limited", big));
        assertEquals(200, post(app, "/open", big));
    }

    @Test
    public void toleratesAMalformedContentLength() throws Exception {
        Object read = new AgentBodyLimitInterceptor(LIMIT).aroundReadFrom(context("abc", new byte[10], new AtomicBoolean()));
        assertEquals(10, read);
    }
}
