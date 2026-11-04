package com.hmdm.rest.filter;

import com.google.inject.servlet.UriPatternType;
import com.hmdm.plugin.PluginList;
import com.hmdm.plugins.audit.rest.filter.ResourceAuditInfo;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.process.Inflector;
import org.glassfish.jersey.server.model.Resource;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The audit rules against every spelling of an audited request that the edge lets through and Jersey routes to the
 * same resource method: a percent-escaped character in any segment ({@code command%73}), either hex case, and a
 * trailing slash. Each request runs through the real layers in memory: {@link AmbiguousPathFilter}, Guice's own
 * path matching (which decides whether the auth filters run), {@link ResourceAuditInfo#findAuditInfo} as
 * {@code AuditFilter} calls it, and Jersey's routing over the real JAX-RS route table (every {@code @Path} of every
 * resource class, core and plugins; the handlers are stubs, so no resource is constructed). A spelling that Jersey
 * routes to an audited method must get the same audit rule as the plain path.
 */
public class AuditRuleSpellingTest {

    /** A plain request for each audit rule that has a route, as the console sends it. */
    private static final Map<ResourceAuditInfo, String[]> SAMPLES = new LinkedHashMap<>();

    /** Rules with no route in this tree: no request can reach them, whatever its spelling. */
    private static final Set<ResourceAuditInfo> NO_ROUTE = EnumSet.of(
            ResourceAuditInfo.DEVICE_PASSWORD_RESET,   // the devicereset plugin is not part of MDMesh
            ResourceAuditInfo.DEVICE_FACTORY_RESET,
            ResourceAuditInfo.DEVICE_LOCK,
            ResourceAuditInfo.UPDATE_FILE);            // FilesResource has /update, not /move

    static {
        sample(ResourceAuditInfo.LOGIN, "POST", "/rest/public/auth/login");
        sample(ResourceAuditInfo.JWT_LOGIN, "POST", "/rest/public/jwt/login");
        sample(ResourceAuditInfo.UPDATE_DEVICE, "PUT", "/rest/private/devices");
        sample(ResourceAuditInfo.REMOVE_DEVICE, "DELETE", "/rest/private/devices/1");
        sample(ResourceAuditInfo.UPDATE_CONFIG, "PUT", "/rest/private/configurations");
        sample(ResourceAuditInfo.COPY_CONFIG, "PUT", "/rest/private/configurations/copy");
        sample(ResourceAuditInfo.REMOVE_CONFIG, "DELETE", "/rest/private/configurations/1");
        sample(ResourceAuditInfo.UPDATE_APP, "PUT", "/rest/private/applications/android");
        sample(ResourceAuditInfo.UPDATE_WEBAPP, "PUT", "/rest/private/applications/web");
        sample(ResourceAuditInfo.REMOVE_APP, "DELETE", "/rest/private/applications/1");
        sample(ResourceAuditInfo.UPDATE_APP_VERSION, "PUT", "/rest/private/applications/versions");
        sample(ResourceAuditInfo.REMOVE_APP_VERSION, "DELETE", "/rest/private/applications/versions/1");
        sample(ResourceAuditInfo.REMOVE_FILE, "POST", "/rest/private/web-ui-files/remove");
        sample(ResourceAuditInfo.UPDATE_APP_CONFIG, "POST", "/rest/private/applications/configurations");
        sample(ResourceAuditInfo.UPDATE_VERSION_CONFIG, "POST", "/rest/private/applications/version/configurations");
        sample(ResourceAuditInfo.UPDATE_DESIGN, "POST", "/rest/private/settings/design");
        sample(ResourceAuditInfo.UPDATE_USERROLES, "POST", "/rest/private/settings/userRoles/common");
        sample(ResourceAuditInfo.UPDATE_LANGUAGE, "POST", "/rest/private/settings/lang");
        sample(ResourceAuditInfo.UPDATE_PLUGINS, "POST", "/rest/plugin/main/private/disabled");
        sample(ResourceAuditInfo.UPDATE_USER, "PUT", "/rest/private/users");
        sample(ResourceAuditInfo.REMOVE_USER, "DELETE", "/rest/private/users/other/1");
        sample(ResourceAuditInfo.UPDATE_GROUP, "PUT", "/rest/private/groups");
        sample(ResourceAuditInfo.REMOVE_GROUP, "DELETE", "/rest/private/groups/1");
        sample(ResourceAuditInfo.PASSWORD_CHANGED, "PUT", "/rest/private/users/current");
        sample(ResourceAuditInfo.DEVICE_COMMAND, "POST", "/rest/private/agent/v1/devices/h0001/commands");
        sample(ResourceAuditInfo.BULK_DEVICE_COMMAND, "POST", "/rest/private/agent/v1/bulk/commands");
        sample(ResourceAuditInfo.SYNC_DEVICE_APPS, "POST", "/rest/private/agent/v1/devices/h0001/syncApps");
    }

    private static void sample(ResourceAuditInfo rule, String method, String path) {
        SAMPLES.put(rule, new String[]{method, path});
    }

    private static ApplicationHandler routes;

    @BeforeClass
    public static void setUp() {
        routes = new ApplicationHandler(routeTable());
    }

    @Test
    public void everyRuleHasASampleThatRoutesToAResourceMethod() {
        for (ResourceAuditInfo rule : ResourceAuditInfo.values()) {
            assertTrue(rule + " has a sample or no route", SAMPLES.containsKey(rule) || NO_ROUTE.contains(rule));
        }
        for (Map.Entry<ResourceAuditInfo, String[]> e : SAMPLES.entrySet()) {
            String method = e.getValue()[0];
            String path = e.getValue()[1];
            assertTrue(e.getKey() + " audits its plain path", ResourceAuditInfo.findAuditInfo(method, path).isPresent());
            assertNotNull(e.getKey() + ": " + method + " " + path + " routes to a resource method",
                    routedTo(method, path));
        }
    }

    @Test
    public void everySpellingThatReachesAnAuditedMethodIsAudited() {
        List<String> missed = new ArrayList<>();
        int live = 0;
        for (Map.Entry<ResourceAuditInfo, String[]> e : SAMPLES.entrySet()) {
            String method = e.getValue()[0];
            String plain = e.getValue()[1];
            String target = routedTo(method, plain);
            assertNotNull(method + " " + plain + " routes", target);
            Optional<ResourceAuditInfo> expected = ResourceAuditInfo.findAuditInfo(method, plain);
            List<String> plainFilters = guiceFilters(plain);
            for (String variant : spellings(plain)) {
                if (!AmbiguousPathFilter.isUnambiguous(variant)) {
                    continue;                                   // refused at the edge, never routed
                }
                if (!target.equals(routedTo(method, variant))) {
                    continue;                                   // Jersey doesn't take it to the same method
                }
                live++;
                if (System.getProperty("debugRoutes") != null) {
                    System.out.println("LIVE " + method + " " + variant + " -> " + target);
                }
                // The request is still authorised exactly as the plain one: the same auth filters run on it
                assertEquals(variant + " gets the plain path's filters", plainFilters, guiceFilters(variant));
                Optional<ResourceAuditInfo> rule = ResourceAuditInfo.findAuditInfo(method, variant);
                if (!rule.equals(expected)) {
                    missed.add(e.getKey() + ": " + method + " " + variant + " -> " + target + " audited as " + rule);
                }
            }
        }
        System.out.println("AuditRuleSpellingTest: " + live + " live spellings, " + missed.size() + " not audited");
        missed.forEach(m -> System.out.println("  NOT AUDITED " + m));
        assertTrue("checked the live spellings (" + live + ")", live > 50);
        assertEquals("spellings that run an audited action with no audit record", Collections.emptyList(), missed);
    }

    @Test
    public void jerseyDoesNotRouteAPercentEscapedLiteral() {
        // Jersey matches the @Path literals against the raw (encoded) path, so command%73 is a 404, not a command
        String path = "/rest/private/agent/v1/devices/X/command%73";
        assertTrue("the edge lets it through", AmbiguousPathFilter.isUnambiguous(path));
        assertNotNull(routedTo("POST", "/rest/private/agent/v1/devices/X/commands"));
        assertEquals(null, routedTo("POST", path));
        assertEquals(null, routedTo("POST", "/rest/%70rivate/agent/v1/devices/X/commands"));
        assertEquals(null, routedTo("POST", "/rest/public/auth/logi%6E"));
    }

    @Test
    public void aTrailingSlashIsRoutedToTheSameMethodAndAudited() {
        String[][] requests = {
                {"POST", "/rest/public/auth/login/", "LOGIN"},
                {"PUT", "/rest/private/users/current/", "PASSWORD_CHANGED"},
                {"PUT", "/rest/private/users/", "UPDATE_USER"},
                {"POST", "/rest/private/settings/design/", "UPDATE_DESIGN"},
        };
        for (String[] r : requests) {
            String plain = r[1].substring(0, r[1].length() - 1);
            assertTrue(r[1], AmbiguousPathFilter.isUnambiguous(r[1]));
            assertNotNull(r[1], routedTo(r[0], plain));
            assertEquals(r[1], routedTo(r[0], plain), routedTo(r[0], r[1]));
            assertEquals(r[1], guiceFilters(plain), guiceFilters(r[1]));
            assertEquals(r[1], Optional.of(ResourceAuditInfo.valueOf(r[2])),
                    ResourceAuditInfo.findAuditInfo(r[0], r[1]));
        }
    }

    // ---- spellings --------------------------------------------------------------------------------------------------

    /**
     * {@code path} with each character after {@code /rest/} percent-escaped in turn (upper and lower hex), and with a
     * trailing slash.
     */
    static List<String> spellings(String path) {
        List<String> out = new ArrayList<>();
        int from = "/rest/".length();
        for (int i = from; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                continue;
            }
            String hex = String.format("%02X", (int) c);
            out.add(path.substring(0, i) + "%" + hex + path.substring(i + 1));
            out.add(path.substring(0, i) + "%" + hex.toLowerCase(Locale.ROOT) + path.substring(i + 1));
        }
        out.add(path + "/");
        return out;
    }

    // ---- Guice: which pattern filters run ---------------------------------------------------------------------------

    private static final String[] AUTH_PATTERNS = {"/rest/private/*", "/rest/plugin/main/private/*"};

    /**
     * The auth patterns that Guice applies to {@code uri}, decided as {@code FilterDefinition} decides it (its
     * package-private {@code ServletUtils.getContextRelativePath} and {@code UriPatternType.SERVLET} matcher, called
     * reflectively).
     */
    private static List<String> guiceFilters(String uri) {
        List<String> out = new ArrayList<>();
        try {
            Class<?> utils = Class.forName("com.google.inject.servlet.ServletUtils");
            Method relative = utils.getDeclaredMethod("getContextRelativePath", HttpServletRequest.class);
            relative.setAccessible(true);
            String path = (String) relative.invoke(null, request(uri));
            Method get = UriPatternType.class.getDeclaredMethod("get", UriPatternType.class, String.class);
            get.setAccessible(true);
            for (String pattern : AUTH_PATTERNS) {
                Object matcher = get.invoke(null, UriPatternType.SERVLET, pattern);
                Method matches = matcher.getClass().getDeclaredMethod("matches", String.class);
                matches.setAccessible(true);
                if (path != null && (Boolean) matches.invoke(matcher, path)) {
                    out.add(pattern);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Guice's own path matching", e);
        }
        return out;
    }

    private static HttpServletRequest request(String uri) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getRequestURI": return uri;
                        case "getContextPath": return "";
                        default: return null;
                    }
                });
    }

    // ---- Jersey: the real route table with stub handlers ------------------------------------------------------------

    /** The resource method Jersey routes {@code method path} to ("Class#method"), or null when none. */
    private static String routedTo(String method, String path) {
        try {
            // As Jersey's ServletContainer (a filter on /rest/*) builds it: the raw request URI, from getRequestURI()
            URI requestUri = UriBuilder.fromUri("http://h/").replacePath(path).build();
            ContainerRequest request = new ContainerRequest(URI.create("http://h/rest/"), requestUri, method, null,
                    new MapPropertiesDelegate());
            request.header("Content-Type", MediaType.APPLICATION_JSON);
            request.header("Accept", "*/*");
            request.setEntityStream(new java.io.ByteArrayInputStream(new byte[0]));
            ContainerResponse response = routes.apply(request).get();
            if (response.getStatus() != 200 && System.getProperty("debugRoutes") != null) {
                System.out.println("ROUTE " + method + " " + path + " -> " + response.getStatus());
            }
            return response.getStatus() == 200 ? (String) response.getEntity() : null;
        } catch (Exception e) {
            throw new AssertionError(method + " " + path, e);
        }
    }

    /**
     * Every resource class the application registers (HMDMApplication scans {@link PluginList#PACKAGES}), rebuilt
     * with the same paths, HTTP methods and consumed types, each method answering its own name. Jersey matches
     * these exactly as it matches the real resources, and nothing needs constructing.
     */
    private static ResourceConfig routeTable() {
        ResourceConfig config = new ResourceConfig();
        Set<Class<?>> scanned = new ResourceConfig().packages(PluginList.PACKAGES.toArray(new String[0])).getClasses();
        int methods = 0;
        for (Class<?> type : scanned) {
            Path classPath = type.getAnnotation(Path.class);
            if (classPath == null) {
                continue;
            }
            Resource.Builder resource = Resource.builder(classPath.value());
            Map<String, Resource.Builder> children = new LinkedHashMap<>();
            for (Method m : type.getMethods()) {
                String http = httpMethod(m);
                if (http == null) {
                    continue;
                }
                Path methodPath = m.getAnnotation(Path.class);
                Resource.Builder target = methodPath == null ? resource
                        : children.computeIfAbsent(methodPath.value(), resource::addChildResource);
                Consumes consumes = m.getAnnotation(Consumes.class);
                if (consumes == null) {
                    consumes = type.getAnnotation(Consumes.class);
                }
                String name = type.getSimpleName() + "#" + m.getName();
                org.glassfish.jersey.server.model.ResourceMethod.Builder rm = target.addMethod(http)
                        .produces(MediaType.TEXT_PLAIN_TYPE)
                        .handledBy(new Inflector<ContainerRequestContext, Response>() {
                            @Override
                            public Response apply(ContainerRequestContext request) {
                                return Response.ok(name).build();
                            }
                        });
                if (consumes != null) {
                    rm.consumes(consumes.value());
                }
                methods++;
            }
            config.registerResources(resource.build());
        }
        assertTrue("found the routes (" + methods + ")", methods > 100);
        return config;
    }

    private static String httpMethod(Method m) {
        for (Annotation a : m.getAnnotations()) {
            HttpMethod http = a.annotationType().getAnnotation(HttpMethod.class);
            if (http != null) {
                return http.value();
            }
        }
        return null;
    }

}
