package com.hmdm.rest.filter;

import com.google.inject.servlet.UriPatternType;
import com.hmdm.persistence.domain.User;
import com.hmdm.plugin.PluginList;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.security.SecurityContext;
import com.mdmesh.fixture.FixturePrivateResource;
import com.mdmesh.fixture.FixturePublicResource;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The path-parameter bypass of the private routes' filters, end to end in memory, on the real filter patterns and the
 * real JAX-RS routes:
 * <ul>
 *   <li>Guice matches its filter patterns on the raw URI, so {@code /rest/private;x=y/...} skips them (the hole);</li>
 *   <li>with {@link AmbiguousPathFilter} in front, as web.xml maps it, every such spelling of every private pattern, core
 *       and plugin, is refused with 400 and nothing behind it runs, while the plain paths still pass every filter;</li>
 *   <li>Jersey routes {@code private;x=y/users/current} to the resource, and {@link PrivateRouteGuard} refuses it (403)
 *       when no user is signed in, while signed-in and public requests still reach their resources.</li>
 * </ul>
 */
public class PrivateRouteBypassTest {

    private static List<String> protectedPatterns;

    @BeforeClass
    public static void setUp() throws Exception {
        protectedPatterns = protectedPatterns();
        assertTrue("found the core private pattern", protectedPatterns.contains("/rest/private/*"));
        assertTrue("found a plugin's private patterns", protectedPatterns.contains("/rest/plugins/push/private/*"));
        assertTrue("found an exact plugin pattern",
                protectedPatterns.contains("/rest/plugins/deviceinfo/deviceinfo-plugin-settings/private"));
    }

    // ---- servlet layer ----------------------------------------------------------------------------------------------

    @Test
    public void theAppRegistersTheGuard() {
        // HMDMApplication registers providers by scanning PluginList.PACKAGES (com.hmdm, and com.mdmesh for a fork's
        // plugins): the tests below add the guard by hand.
        assertTrue(PluginList.PACKAGES.contains("com.hmdm") && PluginList.PACKAGES.contains("com.mdmesh"));
        assertTrue(new ResourceConfig().packages(PluginList.PACKAGES.toArray(new String[0])).getClasses()
                .contains(PrivateRouteGuard.class));
    }

    @Test
    public void aForkPluginsPrivatePatternIsRefusedInEveryAmbiguousSpelling() throws Exception {
        // A fork plugin (com.mdmesh, plugins/fork/) puts its private routes behind AuthFilter the same way: the test
        // fixture's module, which the source scan above (src/main/java only) doesn't read.
        String pattern = "/rest/plugins/fixture/private/*";
        List<String> saved = protectedPatterns;
        protectedPatterns = new ArrayList<>(saved);
        protectedPatterns.add(pattern);
        try {
            String plain = concrete(pattern);
            Outcome ok = throughWebXmlOrder(plain);
            assertEquals(0, ok.status);
            assertEquals(Collections.singletonList(pattern), ok.filtered);
            for (String variant : variants(plain)) {
                Outcome o = throughWebXmlOrder(variant);
                assertEquals(variant, 400, o.status);
                assertFalse(variant + ": never reaches the REST layer", o.reachedRest);
            }
        } finally {
            protectedPatterns = saved;
        }
    }

    @Test
    public void theGuardRefusesAForkPluginsPrivateResourceWithNoSignedInUser() throws Exception {
        ApplicationHandler app = new ApplicationHandler(new ResourceConfig(FixturePrivateResource.class,
                FixturePublicResource.class, PrivateRouteGuard.class));
        for (String path : new String[]{"plugins/fixture/private/ping", "plugins/fixture/private;x=y/ping",
                "plugins;x/fixture/private/ping", "plugins/fixture;x/private/ping"}) {
            assertEquals(path, 403, get(app, path).getStatus());
        }
        SecurityContext.init(user());
        try {
            assertEquals(200, get(app, "plugins/fixture/private/ping").getStatus());
        } finally {
            SecurityContext.release();
        }
    }

    @Test
    public void guiceMatchesTheRawUriSoAPathParameterSkipsTheFilters() throws Exception {
        // The hole this patch closes: the same resource, spelled with a path parameter, gets none of the filters.
        Outcome plain = throughGuice("/rest/private/users/current");
        assertEquals(Collections.singletonList("/rest/private/*"), plain.filtered);
        assertTrue(plain.reachedRest);

        Outcome bypass = throughGuice("/rest/private;x=y/users/current");
        assertTrue("Guice does not apply /rest/private/* to it", bypass.filtered.isEmpty());
        assertTrue("and the request goes on to the REST layer", bypass.reachedRest);
    }

    @Test
    public void everyPathParameterSpellingOfEveryPrivatePatternIsRefusedFirst() throws Exception {
        int checked = 0;
        for (String pattern : protectedPatterns) {
            String plain = concrete(pattern);
            Outcome ok = throughWebXmlOrder(plain);
            assertEquals(plain + " passes its filters", 0, ok.status);
            assertFalse(plain + " passes its filters", ok.filtered.isEmpty());
            assertTrue(plain + " reaches the REST layer", ok.reachedRest);

            for (String variant : variants(plain)) {
                Outcome o = throughWebXmlOrder(variant);
                assertEquals(variant, 400, o.status);
                assertTrue(variant + ": no filter behind it ran", o.filtered.isEmpty());
                assertFalse(variant + ": never reaches the REST layer", o.reachedRest);
                checked++;
            }
        }
        assertTrue("checked the spellings (" + checked + ")", checked > 50);
    }

    // ---- REST layer -------------------------------------------------------------------------------------------------

    @Path("/private/users")
    public static class Users {
        @GET
        @Path("current")
        public String current() {
            return "current user";
        }
    }

    @Path("/plugins/push")
    public static class PushPlugin {
        @GET
        @Path("/private/search")
        public String search() {
            return "messages";
        }
    }

    @Path("/public/agent/v1")
    public static class Agent {
        @GET
        @Path("checkin")
        public String checkin() {
            return "checked in";
        }
    }

    @Test
    public void jerseyRoutesAPathParameterToThePrivateResource() throws Exception {
        // Why the guard is needed at all: without it, the resource runs.
        ApplicationHandler bare = new ApplicationHandler(new ResourceConfig(Users.class, PushPlugin.class));
        assertEquals(200, get(bare, "private;x=y/users/current").getStatus());
        assertEquals(200, get(bare, "plugins;x/push/private;y/search").getStatus());
    }

    @Test
    public void theGuardRefusesAPrivateResourceWithNoSignedInUser() throws Exception {
        ApplicationHandler app = guarded();
        for (String path : new String[]{"private/users/current", "private;x=y/users/current",
                "private/users;x/current", "plugins/push/private/search", "plugins/push/private;x/search",
                "plugins;x/push/private/search"}) {
            assertEquals(path, 403, get(app, path).getStatus());
        }
    }

    @Test
    public void theGuardLetsASignedInUserAndPublicRoutesThrough() throws Exception {
        ApplicationHandler app = guarded();
        assertEquals(200, get(app, "public/agent/v1/checkin").getStatus());

        SecurityContext.init(user());
        try {
            assertEquals(200, get(app, "private/users/current").getStatus());
            assertEquals(200, get(app, "plugins/push/private/search").getStatus());
        } finally {
            SecurityContext.release();
        }
    }

    @Test
    public void aContextWithoutAUserIsNotASignedInUser() throws Exception {
        SecurityContext.init(1);   // the customer-only context some device routes set up
        try {
            assertEquals(403, get(guarded(), "private/users/current").getStatus());
        } finally {
            SecurityContext.release();
        }
    }

    @Test
    public void everyRealPrivateRouteCountsAsPrivateForTheGuard() throws Exception {
        List<String> routes = privateRoutes();
        assertTrue("found the private routes (" + routes.size() + ")", routes.size() > 40);
        for (String route : routes) {
            // Jersey's path below /rest, with a path parameter on the "private" segment
            String path = route.substring("/rest/".length()).replace("/private/", "/private;x=y/");
            if (path.endsWith("/private")) {
                path = path + ";x=y";
            }
            ContainerRequest request = new ContainerRequest(URI.create("http://h/rest/"),
                    URI.create("http://h/rest/" + path.replace("{", "%7B").replace("}", "%7D")), "GET", null,
                    new MapPropertiesDelegate());
            assertTrue(route, PrivateRouteGuard.isPrivate(request));
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static final class Outcome {
        int status;
        boolean reachedRest;
        List<String> filtered = new ArrayList<>();
    }

    /** The web.xml order: AmbiguousPathFilter, then the Guice filter (its pattern filters), then the REST layer. */
    private static Outcome throughWebXmlOrder(String uri) throws Exception {
        Outcome outcome = new Outcome();
        new AmbiguousPathFilter().doFilter(request(uri), response(outcome),
                (req, res) -> guice((HttpServletRequest) req, outcome));
        return outcome;
    }

    private static Outcome throughGuice(String uri) throws Exception {
        Outcome outcome = new Outcome();
        guice(request(uri), outcome);
        return outcome;
    }

    /**
     * What the Guice filter does with the request: runs each pattern filter whose pattern matches, decided exactly as
     * {@code FilterDefinition.getFilterIfMatching} decides it (the request's context-relative path from
     * {@code ServletUtils}, matched by the {@code UriPatternType.SERVLET} matcher, both called reflectively since
     * they are package-private), then passes the request on to the REST layer. No injector: Guice 4's bytecode
     * generation needs JDK internals opened on Java 17.
     */
    private static void guice(HttpServletRequest request, Outcome outcome) {
        try {
            Class<?> utils = Class.forName("com.google.inject.servlet.ServletUtils");
            Method relative = utils.getDeclaredMethod("getContextRelativePath", HttpServletRequest.class);
            relative.setAccessible(true);
            String path = (String) relative.invoke(null, request);
            Method get = UriPatternType.class.getDeclaredMethod("get", UriPatternType.class, String.class);
            get.setAccessible(true);
            for (String pattern : protectedPatterns) {
                Object matcher = get.invoke(null, UriPatternType.SERVLET, pattern);
                Method matches = matcher.getClass().getDeclaredMethod("matches", String.class);
                matches.setAccessible(true);
                if (path != null && (Boolean) matches.invoke(matcher, path)) {
                    outcome.filtered.add(pattern);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Guice's own path matching", e);
        }
        outcome.reachedRest = true;
    }

    /** A request path the pattern protects: {@code /*} becomes {@code /x}. */
    private static String concrete(String pattern) {
        return pattern.endsWith("/*") ? pattern.substring(0, pattern.length() - 1) + "x" : pattern;
    }

    /** {@code path} with a path parameter, or another ambiguous spelling, in each segment in turn. */
    private static List<String> variants(String path) {
        List<String> out = new ArrayList<>();
        String[] segments = path.substring(1).split("/");
        for (int i = 0; i < segments.length; i++) {
            for (String spelling : new String[]{";x=y", ";", "%3Bx", "%2e", "/.", "//", "\\"}) {
                String[] copy = segments.clone();
                copy[i] = copy[i] + spelling;
                out.add("/" + String.join("/", copy));
            }
        }
        out.add(path.replaceFirst("^/rest/", "/rest/x/../"));
        return out;
    }

    private static ApplicationHandler guarded() {
        return new ApplicationHandler(new ResourceConfig(Users.class, PushPlugin.class, Agent.class,
                PrivateRouteGuard.class));
    }

    private static ContainerResponse get(ApplicationHandler app, String path) throws Exception {
        return app.apply(new ContainerRequest(URI.create("http://h/rest/"), URI.create("http://h/rest/" + path),
                "GET", null, new MapPropertiesDelegate())).get();
    }

    private static User user() {
        User u = new User();
        u.setId(1);
        u.setLogin("admin");
        u.setCustomerId(1);
        u.setUserRole(new UserRole());
        return u;
    }

    private static HttpServletRequest request(String uri) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getRequestURI": return uri;
                        case "getContextPath": return "";
                        case "getMethod": return "GET";
                        case "getAttributeNames":
                        case "getHeaderNames":
                        case "getParameterNames": return Collections.emptyEnumeration();
                        default: return null;
                    }
                });
    }

    private static HttpServletResponse response(Outcome outcome) {
        return (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (p, m, a) -> {
                    if (m.getName().equals("setStatus") || m.getName().equals("sendError")) {
                        outcome.status = (Integer) a[0];
                    }
                    return null;
                });
    }

    private static final Pattern FILTER_THROUGH_AUTH = Pattern.compile(
            "\\.filter\\(\\s*\"([^\"]+)\"\\s*\\)\\s*\\.through\\(\\s*AuthFilter\\.class");
    private static final Pattern PROTECTED_LIST = Pattern.compile(
            "protectedResources\\s*=\\s*Arrays\\.asList\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("\"([^\"]+)\"");

    /** Every pattern a REST module puts behind AuthFilter, core and plugins, read from the module sources. */
    private static List<String> protectedPatterns() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (File source : sources()) {
            if (!source.getName().endsWith("Module.java")) {
                continue;
            }
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            Matcher m = FILTER_THROUGH_AUTH.matcher(text);
            while (m.find()) {
                out.add(m.group(1));
            }
            Matcher list = PROTECTED_LIST.matcher(text);
            while (list.find()) {
                Matcher s = STRING.matcher(list.group(1));
                while (s.find()) {
                    out.add(s.group(1));
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Every JAX-RS route in the tree's main sources (core, plugins, and a fork's com.mdmesh plugins under plugins/fork/)
     * with a {@code private} segment, as /rest/... paths.
     */
    private static List<String> privateRoutes() throws Exception {
        List<String> out = new ArrayList<>();
        Pattern pkg = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
        for (File source : sources()) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            if (!text.contains("@Path(")) {
                continue;
            }
            Matcher p = pkg.matcher(text);
            assertTrue(p.find());
            Class<?> type = Class.forName(p.group(1) + "." + source.getName().replaceFirst("\\.java$", ""), false,
                    PrivateRouteBypassTest.class.getClassLoader());
            Path classPath = type.getAnnotation(Path.class);
            if (classPath == null) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                Path methodPath = method.getAnnotation(Path.class);
                String route = ("/rest/" + classPath.value() + "/" + (methodPath == null ? "" : methodPath.value()))
                        .replaceAll("/{2,}", "/").replaceAll("/$", "");
                if (("/" + route + "/").contains("/private/")) {
                    out.add(route);
                }
            }
        }
        return out.stream().distinct().collect(Collectors.toList());
    }

    private static List<File> sources() throws IOException {
        java.nio.file.Path root = repoRoot().toPath();
        try (Stream<java.nio.file.Path> walk = Files.walk(root)) {
            return walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String s = root.relativize(p).toString().replace('\\', '/');
                        return s.contains("/src/main/java/") && !s.contains("/target/") && !s.startsWith("agent-android/")
                                && !s.contains("node_modules/") && !s.startsWith(".");
                    })
                    .map(java.nio.file.Path::toFile)
                    .collect(Collectors.toList());
        }
    }

    private static File repoRoot() {
        File dir = new File(System.getProperty("basedir", ".")).getAbsoluteFile();
        while (dir != null && !(new File(dir, "server").isDirectory() && new File(dir, "plugins").isDirectory())) {
            dir = dir.getParentFile();
        }
        assertTrue("found the repository root", dir != null);
        return dir;
    }
}
