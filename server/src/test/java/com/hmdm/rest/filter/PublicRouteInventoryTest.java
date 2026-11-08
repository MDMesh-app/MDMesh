package com.hmdm.rest.filter;

import com.example.brokenplugin.PublicServletsModule;
import com.example.brokenplugin.indirect.IndirectServletPlugin;
import com.example.brokenplugin.nested.NestedServletPlugin;
import com.google.inject.Module;
import com.google.inject.servlet.ServletModule;
import com.hmdm.HMDMApplication;
import com.hmdm.rest.resource.PasswordResetResource;
import com.mdmesh.fixture.FixturePublicResource;
import io.swagger.jaxrs.listing.ApiListingResource;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.websocket.server.ServerEndpoint;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards the set of routes reachable without a console session. Every JAX-RS route ({@code @Path} on any class under
 * {@code com.hmdm} or {@code com.mdmesh}, which Jersey scans as a whole), servlet ({@code @WebServlet}, mapped in
 * core's {@code web.xml}, or served by a {@code ServletModule}), WebSocket endpoint ({@code @ServerEndpoint}) and
 * plugin {@code publicResources} registration in the source tree must either sit below a {@code private} segment
 * (session-checked), be switched off by {@link LegacyDeviceApiFilter}, or be kept on purpose: core's in
 * {@link #KEPT_PUBLIC} below, a fork plugin's (sources under {@code plugins/fork/}) in the fork's own
 * {@code plugins/fork/public-routes.txt}, which core ships empty. A new public route therefore fails this test until
 * someone decides which it is. The scan reads sources, so it is checked against what the server will serve: each
 * resource class Jersey serves must be one the scan saw, or be listed with its reason. A {@code ServletModule}'s
 * servlets are read from its bindings, through Guice's servlet SPI ({@link PrivateRouteFilterTest#mappings}), so the
 * pattern is the one served however the module wrote it; each is a route of the module's source, which the scan must
 * have seen; every {@code ServletModule} class on the classpath under the server's packages must be one of those
 * modules ({@link PrivateRouteFilterTest#servletModules}). That a {@code private} segment really is session-checked is
 * {@link PrivateRouteFilterTest}'s. Also pins that {@link AmbiguousPathFilter}, {@link SecurityHeadersFilter} and then
 * this filter are the first filters in {@code web.xml}, ahead of the Guice filter.
 */
public class PublicRouteInventoryTest {

    /** Public routes MDMesh keeps (prefixes: the route itself or anything below it). */
    private static final List<String> KEPT_PUBLIC = Arrays.asList(
            "/rest/public/agent/v1",            // MDMesh agent: enroll + check-in (token / per-device secret)
            "/agent/ws/{deviceNumber}",         // MDMesh agent wake socket (per-device secret)
            "/files",                           // APK and file downloads referenced by the QR / configs
            "/rest/public/auth",                // console sign-in
            "/rest/public/jwt",                 // API client sign-in
            "/rest/public/passwordReset",       // console password reset
            "/rest/public/name",                // health / branding
            "/rest/public/logo",                // branding
            "/rest/plugin/main/public/registered", // plugin list for the console shell
            "/rest/plugins/messaging/{id}",     // console delete; checks the session's permission in the resource
            // web.xml's UpdateProxyServlet: passes /update/* to the loopback updater supervisor (native installs),
            // which checks the session cookie, CSRF header and recovery token itself
            "/update",
            // Swagger's API description: lists only @Api resources; served by a library class, see LIBRARY_RESOURCES
            "/rest/swagger.{type:json|yaml}"
    );

    /**
     * Resources Jersey serves that no source in this tree declares, so the source scan can't see them. Their routes are
     * checked as core's, against {@link #KEPT_PUBLIC}.
     */
    private static final Map<Class<?>, String> LIBRARY_RESOURCES = Collections.<Class<?>, String>singletonMap(
            ApiListingResource.class, "Swagger's /rest/swagger.json and .yaml, registered by HMDMApplication");

    /** A fork's plugins live here (plugins/fork/README.md); their public routes are declared in {@link #FORK_ROUTES}. */
    static final String FORK_DIR = "plugins/fork/";
    static final String FORK_ROUTES = "plugins/fork/public-routes.txt";
    /** Core's deployment descriptor; its servlet mappings are core routes. */
    static final String WEB_XML = "server/src/main/webapp/WEB-INF/web.xml";

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern PUBLIC_RESOURCES = Pattern.compile(
            "publicResources\\s*=\\s*(?:Arrays\\.asList|Collections\\.singletonList|List\\.of)\\(([^)]*)\\)",
            Pattern.DOTALL);
    /** A route of one plugin: {@code /rest/plugins/<id>} or below it; group 1 is the id. */
    private static final Pattern PLUGIN_ROUTE = Pattern.compile("/rest/plugins/([A-Za-z0-9_-]+)(/.*)?");
    private static final Pattern STRING = Pattern.compile("\"([^\"]+)\"");

    @Test
    public void everyPublicRouteIsLegacyOrDeliberatelyKept() throws Exception {
        File root = repoRoot();
        Scan scan = scan(root, true);
        for (Class<?> library : LIBRARY_RESOURCES.keySet()) {
            for (String route : routesOf(library)) {
                scan.routes.add(new Route(route, false));
            }
        }
        // Fails unless every ServletModule class is one the server installs on its own.
        List<Module> modules = PrivateRouteFilterTest.servletModules();
        assertEquals("servlets served by a module whose source the scan never saw", new TreeSet<String>(),
                addServlets(scan, PrivateRouteFilterTest.mappings(modules)));
        assertTrue("core's long-polling servlet is a core route", scan.routes.stream()
                .anyMatch(r -> r.path.equals("/rest/notification/polling/*") && !r.fork));
        List<String> routes = scan.paths();
        assertTrue("the scan found the REST resources (" + scan.classes + ")", scan.classes > 30);
        assertTrue("the scan saw the agent API", routes.contains("/rest/public/agent/v1/checkin"));
        assertTrue("the scan saw the legacy sync API", routes.contains("/rest/public/sync/info"));
        assertTrue("the scan saw the legacy launcher QR", routes.contains("/rest/public/qr/json/{id}"));
        assertTrue("the servlet check saw the file downloads", routes.contains("/files/*"));
        assertTrue("the scan saw web.xml's servlets", routes.contains("/update/*"));

        assertEquals(FORK_ROUTES + " lines that don't name one fork plugin's routes (/rest/plugins/<id> or below, <id> "
                + "an id no core route uses)", new ArrayList<String>(), overBroad(forkDeclared(root), scan));
        assertEquals("public routes that are neither legacy (LegacyDeviceApiFilter.LEGACY_PREFIXES) nor kept: core's "
                + "belong in PublicRouteInventoryTest.KEPT_PUBLIC, a fork plugin's (under " + FORK_DIR + ") in "
                + FORK_ROUTES, new TreeSet<String>(), refused(scan, forkDeclared(root)));
    }

    /**
     * Jersey serves classes, and the scan reads sources: a plugin symlinked into the tree, written in Kotlin or bundled
     * only as a dependency would be served unchecked. So every resource Jersey will serve must be one the scan loaded,
     * or a library's listed in {@link #LIBRARY_RESOURCES} with its reason. The one exception is this module's own test
     * classes (the fixture plugin, the tests' nested resources): Jersey finds them on this classpath, but they are
     * compiled to target/test-classes, which launcher.war never contains.
     */
    @Test
    public void everyResourceJerseyServesWasScanned() throws Exception {
        Set<Class<?>> served = new HashSet<>();
        for (Class<?> type : new HMDMApplication(null).getClasses()) {
            if (type.isAnnotationPresent(Path.class)) {
                served.add(type);
            }
        }
        assertTrue("Jersey serves the resources (" + served.size() + ")", served.size() > 30);
        assertEquals("resources Jersey serves that the source scan (src/main/java, symlinks not followed) never saw: "
                + "move their sources into the tree, or list a library's in LIBRARY_RESOURCES", new TreeSet<String>(),
                unseen(served, scan(repoRoot(), true)));

        assertTrue("the fixture plugin's resources count as test classes",
                isThisModulesTest(FixturePublicResource.class));
        assertTrue("LIBRARY_RESOURCES lists only what Jersey serves", served.containsAll(LIBRARY_RESOURCES.keySet()));
    }

    @Test
    public void aResourceTheScanDidNotSeeIsReported() throws Exception {
        Set<Class<?>> served = new HashSet<>(Arrays.asList(PasswordResetResource.class, ApiListingResource.class,
                FixturePublicResource.class));

        assertEquals(new TreeSet<>(Arrays.asList(PasswordResetResource.class.getName())),
                unseen(served, scan(tree(), true)));
    }

    /** The served resources the scan didn't load, leaving out {@link #LIBRARY_RESOURCES} and this module's tests. */
    static TreeSet<String> unseen(Set<Class<?>> served, Scan scan) {
        TreeSet<String> out = new TreeSet<>();
        for (Class<?> type : served) {
            if (!scan.loaded.contains(type) && !LIBRARY_RESOURCES.containsKey(type) && !isThisModulesTest(type)) {
                out.add(type.getName());
            }
        }
        return out;
    }

    /** Whether {@code type} was compiled from this module's tests: it comes from the same place this test does. */
    static boolean isThisModulesTest(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation()
                .equals(PublicRouteInventoryTest.class.getProtectionDomain().getCodeSource().getLocation());
    }

    // ---- a fork's public routes: plugins/fork/public-routes.txt ------------------------------------------------------

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aForkPluginsPublicRouteDeclaredInTheForkFilePasses() throws Exception {
        File root = tree("/rest/plugins/demo/public");

        assertEquals(new TreeSet<String>(), refused(scan(root, false), forkDeclared(root)));
    }

    @Test
    public void aForkPluginsPublicRouteThatIsNotDeclaredIsRefused() throws Exception {
        File root = tree();

        assertEquals(new TreeSet<>(Arrays.asList("/rest/plugins/demo/public")),
                refused(scan(root, false), forkDeclared(root)));
    }

    @Test
    public void aForkPluginsPublicRouteIsRefusedWithoutTheForkFile() throws Exception {
        File root = tree();
        assertTrue(new File(root, FORK_ROUTES).delete());

        assertEquals(new TreeSet<>(Arrays.asList("/rest/plugins/demo/public")),
                refused(scan(root, false), forkDeclared(root)));
    }

    @Test
    public void theForkFileCannotKeepACoreRoutePublic() throws Exception {
        File root = tree("/rest/plugins/demo/public", "/rest/plugins/coreish/public");
        write(root, "plugins/coreish/src/main/java/com/hmdm/plugins/coreish/CoreishRestModule.java",
                restModule("com.hmdm.plugins.coreish", "CoreishRestModule", "/rest/plugins/coreish/public/*"));

        Scan scan = scan(root, false);

        assertEquals(Arrays.asList("/rest/plugins/coreish/public"), overBroad(forkDeclared(root), scan));
        assertEquals(new TreeSet<>(Arrays.asList("/rest/plugins/coreish/public")), refused(scan, forkDeclared(root)));
    }

    @Test
    public void theForkFileSkipsCommentsAndBlankLinesAndTakesPrefixes() throws Exception {
        File root = tree();
        write(root, FORK_ROUTES, "# a comment: /rest/plugins/demo/public\n\n   \n  /rest/plugins/demo/*  \n");

        assertEquals(Arrays.asList("/rest/plugins/demo/*"), forkDeclared(root));
        assertEquals(new TreeSet<String>(), refused(scan(root, false), forkDeclared(root)));
    }

    @Test
    public void aForkFileLineAboveOnePluginIsReportedAndCoversNothing() throws Exception {
        File root = tree("/*", "/rest", "/rest/plugins/*", "/rest/public", "/rest/plugins/de*");
        Scan scan = scan(root, false);

        assertEquals(Arrays.asList("/*", "/rest", "/rest/plugins/*", "/rest/public", "/rest/plugins/de*"),
                overBroad(forkDeclared(root), scan));
        assertEquals(new TreeSet<>(Arrays.asList("/rest/plugins/demo/public")), refused(scan, forkDeclared(root)));
    }

    @Test
    public void aForkRouteUnderALegacyPrefixIsNotExempt() throws Exception {
        File root = tree("/rest/plugins/demo/public");
        write(root, "plugins/fork/demo/src/main/java/com/mdmesh/demo/SyncishRestModule.java",
                restModule("com.mdmesh.demo", "SyncishRestModule", "/rest/public/sync/demo/*"));
        assertTrue("core's route there would be exempt", LegacyDeviceApiFilter.isLegacyPath("/rest/public/sync/demo"));

        assertEquals(new TreeSet<>(Arrays.asList("/rest/public/sync/demo")),
                refused(scan(root, false), forkDeclared(root)));
    }

    /** A {@code @Path} resource under plugins/fork, read from its compiled class, is a fork route. */
    @Test
    public void aForkPluginsPathResourceIsAForkRoute() throws Exception {
        File root = tree();
        // The real test fixture's class: the scan loads com.mdmesh.fixture.FixturePublicResource by this source's name.
        write(root, "plugins/fork/fixture/src/main/java/com/mdmesh/fixture/FixturePublicResource.java",
                "package com.mdmesh.fixture;\n\n@Path(\"/plugins/fixture/public\")\n"
                        + "public class FixturePublicResource {\n}\n");
        Scan scan = scan(root, true);
        assertTrue(scan.loaded.contains(FixturePublicResource.class));

        assertEquals(new TreeSet<>(Arrays.asList("/rest/plugins/demo/public", "/rest/plugins/fixture/public/ping")),
                refused(scan, forkDeclared(root)));
        write(root, FORK_ROUTES, "/rest/plugins/demo/public\n/rest/plugins/fixture/public\n");
        assertEquals(new TreeSet<String>(), refused(scan, forkDeclared(root)));
    }

    @Test
    public void publicResourcesAreReadInEachListForm() throws Exception {
        File root = tree("/rest/plugins/demo/public");
        write(root, "plugins/fork/demo/src/main/java/com/mdmesh/demo/OneRestModule.java",
                "package com.mdmesh.demo;\n\nclass OneRestModule {\n"
                        + "    private static final List<String> publicResources =\n"
                        + "            Collections.singletonList(\"/rest/plugins/demo/one/*\");\n}\n");
        write(root, "plugins/fork/demo/src/main/java/com/mdmesh/demo/TwoRestModule.java",
                "package com.mdmesh.demo;\n\nclass TwoRestModule {\n"
                        + "    private static final List<String> publicResources = "
                        + "List.of(\"/rest/plugins/demo/two/*\", \"/rest/plugins/demo/three\");\n}\n");

        assertEquals(new TreeSet<>(Arrays.asList("/rest/plugins/demo/one", "/rest/plugins/demo/two",
                "/rest/plugins/demo/three")),
                refused(scan(root, false), forkDeclared(root)));
    }

    // ---- servlets: read from the modules' bindings ------------------------------------------------------------------

    /** A fork module's servlets are fork routes, served with an unqualified serve() or a pattern built at run time. */
    @Test
    public void aForkModulesServletsAreCheckedHoweverTheyAreServed() throws Exception {
        File root = tree("/rest/plugins/demo/public");
        write(root, "plugins/fork/demo/src/main/java/com/example/brokenplugin/PublicServletsModule.java",
                "package com.example.brokenplugin;\n\npublic class PublicServletsModule extends ServletModule {\n}\n");
        Scan scan = scan(root, false);

        assertEquals(new TreeSet<String>(), addServlets(scan, servlets(new PublicServletsModule())));
        assertEquals(new TreeSet<>(Arrays.asList("/rest/plugins/demo/feed", "/rest/plugins/demo/upload")),
                refused(scan, forkDeclared(root)));
        write(root, FORK_ROUTES, "/rest/plugins/demo\n");
        assertEquals(new TreeSet<String>(), refused(scan, forkDeclared(root)));
    }

    @Test
    public void aServletFromAModuleTheScanNeverSawIsReported() throws Exception {
        String why = ": served by " + PublicServletsModule.class.getName() + ", whose source the scan never saw";

        assertEquals(new TreeSet<>(Arrays.asList("/rest/plugins/demo/feed/*" + why, "/rest/plugins/demo/upload" + why)),
                addServlets(scan(tree(), false), servlets(new PublicServletsModule())));
    }

    /**
     * A ServletModule installed from inside another module isn't one of the modules the route tests read, so its
     * servlets would be served unchecked; the class check reports it, even in the same file as one that is installed.
     */
    @Test
    public void aServletModuleInstalledFromInsideAnotherModuleIsReported() {
        List<Module> modules = new NestedServletPlugin().modules();
        assertTrue("Guice serves the nested module's servlet", PrivateRouteFilterTest.mappings(modules).servlets
                .stream().anyMatch(s -> s.binding.getPattern().equals("/rest/plugins/demo/hidden")));

        assertEquals(new TreeSet<>(Arrays.asList(NestedServletPlugin.class.getName() + "$2$1")),
                PrivateRouteFilterTest.uninstalled(fixtureClasses("nested"), installedOnTheirOwn(modules), false));
    }

    /** A subclass of a ServletModule subclass, core's, a fork's or a library's, is checked as a ServletModule. */
    @Test
    public void aSubclassOfAServletModuleSubclassInstalledFromInsideAnotherModuleIsReported() {
        Map<String, Class<?>> found = fixtureClasses("indirect");
        assertEquals(new TreeSet<>(Arrays.asList(IndirectServletPlugin.BaseRestModule.class.getName(),
                IndirectServletPlugin.SubRestModule.class.getName(),
                IndirectServletPlugin.LibrarySubRestModule.class.getName())), found.keySet());

        assertEquals(new TreeSet<>(Arrays.asList(IndirectServletPlugin.SubRestModule.class.getName(),
                IndirectServletPlugin.LibrarySubRestModule.class.getName())),
                PrivateRouteFilterTest.uninstalled(found, installedOnTheirOwn(new IndirectServletPlugin().modules()),
                        false));
        assertEquals(new TreeSet<String>(), PrivateRouteFilterTest.uninstalled(found, Arrays.<Module>asList(
                new IndirectServletPlugin.BaseRestModule(), new IndirectServletPlugin.SubRestModule(),
                new IndirectServletPlugin.LibrarySubRestModule()), false));
    }

    /** The fixtures are this module's test classes, so the real check would skip them: these tests don't. */
    @Test
    public void theRealCheckSkipsOnlyThisModulesTestClasses() {
        Map<String, Class<?>> found = fixtureClasses("nested");
        assertTrue(isThisModulesTest(found.get(NestedServletPlugin.class.getName() + "$2$1")));

        assertEquals(new TreeSet<String>(), PrivateRouteFilterTest.uninstalled(found,
                Collections.<Module>emptyList(), true));
        assertTrue("core's modules are found and aren't test classes", PrivateRouteFilterTest
                .servletModuleClasses(com.hmdm.plugin.PluginList.PACKAGES).values().stream()
                .filter(c -> !isThisModulesTest(c)).count() >= 10);
    }

    /** The ServletModule classes in one fixture package under com.example.brokenplugin. */
    private static Map<String, Class<?>> fixtureClasses(String pkg) {
        return PrivateRouteFilterTest.servletModuleClasses(
                Collections.singletonList("com.example.brokenplugin." + pkg));
    }

    /** The plugin modules the server installs as modules of their own: its ServletModules. */
    private static List<Module> installedOnTheirOwn(List<Module> pluginModules) {
        return pluginModules.stream().filter(m -> m instanceof ServletModule).collect(Collectors.toList());
    }

    // ---- web.xml's servlets ---------------------------------------------------------------------------------------

    /** A servlet web.xml maps is a core route, checked against KEPT_PUBLIC like any other, each pattern of it. */
    @Test
    public void webXmlsServletsAreCoreRoutes() throws Exception {
        File root = tree("/rest/plugins/demo/public");
        write(root, WEB_XML, "<?xml version=\"1.0\"?>\n<web-app>\n"
                + "  <servlet><servlet-name>a</servlet-name><servlet-class>A</servlet-class></servlet>\n"
                + "  <servlet-mapping><servlet-name>a</servlet-name>\n"
                + "    <url-pattern>/hidden/*</url-pattern> <url-pattern>/rest/private/a</url-pattern>\n"
                + "  </servlet-mapping>\n"
                + "  <servlet-mapping><servlet-name>a</servlet-name>\n"
                + "    <url-pattern>/files/a</url-pattern>\n"
                + "  </servlet-mapping>\n"
                + "</web-app>\n");
        Scan scan = scan(root, false);

        assertTrue(scan.routes.stream().anyMatch(r -> r.path.equals("/hidden/*") && !r.fork));
        assertEquals(new TreeSet<>(Arrays.asList("/hidden")), refused(scan, forkDeclared(root)));
        assertTrue("the real web.xml's update proxy is read", webXmlServlets(new File(repoRoot(), WEB_XML))
                .contains("/update/*"));
    }

    @Test
    public void theFiltersAreMappedFirstInWebXml() throws Exception {
        Document doc = parse(new File(repoRoot(), WEB_XML));

        String ambiguous = filterNamed(doc, AmbiguousPathFilter.class.getName());
        String headers = filterNamed(doc, SecurityHeadersFilter.class.getName());
        String legacy = filterNamed(doc, LegacyDeviceApiFilter.class.getName());
        assertTrue("web.xml declares AmbiguousPathFilter", ambiguous != null);
        assertTrue("web.xml declares SecurityHeadersFilter", headers != null);
        assertTrue("web.xml declares LegacyDeviceApiFilter", legacy != null);

        // AmbiguousPathFilter first (no other filter sees an ambiguous path), then SecurityHeadersFilter, then
        // LegacyDeviceApiFilter, all for every path and all ahead of the Guice filter, which runs every Guice-registered
        // filter (auth, IP, plugins).
        NodeList mappings = doc.getElementsByTagName("filter-mapping");
        String[] expected = {ambiguous, headers, legacy};
        for (int i = 0; i < expected.length; i++) {
            Element mapping = (Element) mappings.item(i);
            assertEquals("filter-mapping " + (i + 1), expected[i], text(mapping, "filter-name"));
            assertEquals("filter-mapping " + (i + 1), "/*", text(mapping, "url-pattern"));
        }
    }

    private static String filterNamed(Document doc, String className) {
        NodeList filters = doc.getElementsByTagName("filter");
        for (int i = 0; i < filters.getLength(); i++) {
            Element f = (Element) filters.item(i);
            if (text(f, "filter-class").equals(className)) {
                return text(f, "filter-name");
            }
        }
        return null;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** A route found in a source file, and whether that file is a fork's (under {@link #FORK_DIR}). */
    static final class Route {
        final String path;
        final boolean fork;

        Route(String path, boolean fork) {
            this.path = path;
            this.fork = fork;
        }
    }

    static final class Scan {
        final List<Route> routes = new ArrayList<>();
        /** Each source's top-level class, and whether the source is a fork's. */
        final Map<String, Boolean> sources = new HashMap<>();
        /** The classes read for their annotations (with {@code loadClasses}). */
        final Set<Class<?>> loaded = new HashSet<>();
        int classes;

        List<String> paths() {
            return routes.stream().map(r -> r.path).collect(Collectors.toList());
        }
    }

    /**
     * Every route the sources under {@code root} declare. {@code loadClasses}: read {@code @Path}, {@code @WebServlet}
     * and {@code @ServerEndpoint} from the compiled classes (the real tree); a synthetic tree has registrations only.
     */
    static Scan scan(File root, boolean loadClasses) throws Exception {
        Scan scan = new Scan();
        java.nio.file.Path base = root.toPath();
        for (File source : sources(root)) {
            boolean fork = base.relativize(source.toPath()).toString().replace('\\', '/').startsWith(FORK_DIR);
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            String name = className(source, text);
            scan.sources.put(name, fork);
            if (loadClasses && (text.contains("@Path(") || text.contains("@WebServlet(")
                    || text.contains("@ServerEndpoint("))) {
                scan.classes++;
                Class<?> type = Class.forName(name, false, PublicRouteInventoryTest.class.getClassLoader());
                scan.loaded.add(type);
                for (String route : routesOf(type)) {
                    scan.routes.add(new Route(route, fork));
                }
            }
            Matcher m = PUBLIC_RESOURCES.matcher(text);
            while (m.find()) {
                Matcher s = STRING.matcher(m.group(1));
                while (s.find()) {
                    scan.routes.add(new Route(s.group(1), fork));
                }
            }
        }
        File webXml = new File(root, WEB_XML);
        if (webXml.isFile()) {
            for (String route : webXmlServlets(webXml)) {
                scan.routes.add(new Route(route, false));
            }
        }
        return scan;
    }

    /** The url-patterns of web.xml's servlet mappings. web.xml is core's, so they are core routes. */
    static List<String> webXmlServlets(File webXml) throws Exception {
        List<String> out = new ArrayList<>();
        NodeList mappings = parse(webXml).getElementsByTagName("servlet-mapping");
        for (int i = 0; i < mappings.getLength(); i++) {
            NodeList patterns = ((Element) mappings.item(i)).getElementsByTagName("url-pattern");
            for (int j = 0; j < patterns.getLength(); j++) {
                out.add(patterns.item(j).getTextContent().trim());
            }
        }
        return out;
    }

    private static Document parse(File xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder().parse(xml);
    }

    /**
     * Adds each servlet the modules serve to the scan, as a route of the module's source, a fork's if that source is
     * under {@link #FORK_DIR}. The pattern is the binding's, so it doesn't matter how the module wrote it. Returns the
     * servlets of a module whose source the scan never saw, as {@code "<pattern>: <why>"}: they can't be placed.
     */
    static TreeSet<String> addServlets(Scan scan, PrivateRouteFilterTest.Mappings mappings) {
        TreeSet<String> unplaced = new TreeSet<>();
        for (PrivateRouteFilterTest.ServletMapping servlet : mappings.servlets) {
            Boolean fork = scan.sources.get(topLevel(servlet.module));
            if (fork == null) {
                unplaced.add(servlet.binding.getPattern() + ": served by " + servlet.module
                        + ", whose source the scan never saw");
            } else {
                scan.routes.add(new Route(servlet.binding.getPattern(), fork));
            }
        }
        return unplaced;
    }

    private static String topLevel(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    private static PrivateRouteFilterTest.Mappings servlets(Module... modules) {
        return PrivateRouteFilterTest.mappings(Arrays.asList(modules));
    }

    /**
     * The lines of {@link #FORK_ROUTES}, trimmed: one route or prefix per line, {@code #} comments and blank lines
     * skipped.
     */
    static List<String> forkDeclared(File root) throws IOException {
        File file = new File(root, FORK_ROUTES);
        List<String> out = new ArrayList<>();
        if (!file.isFile()) {
            return out;
        }
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            String route = line.trim();
            if (!route.isEmpty() && !route.startsWith("#")) {
                out.add(route);
            }
        }
        return out;
    }

    /**
     * The public routes nothing keeps: core's must be in {@link #KEPT_PUBLIC}, a fork's in {@link #FORK_ROUTES}. A fork
     * file line that {@link #overBroad} reports covers nothing, and only core's routes get the legacy exemption: a fork
     * route below a legacy prefix would be public once {@code legacy.device.api} is on.
     */
    static TreeSet<String> refused(Scan scan, List<String> forkDeclared) {
        List<String> forkKept = new ArrayList<>(forkDeclared);
        forkKept.removeAll(overBroad(forkDeclared, scan));
        forkKept.replaceAll(PublicRouteInventoryTest::stripWildcard);
        TreeSet<String> refused = new TreeSet<>();
        for (Route r : scan.routes) {
            String route = stripWildcard(r.path);
            if (isPrivate(route) || (!r.fork && LegacyDeviceApiFilter.isLegacyPath(route))) {
                continue;
            }
            if (!covered(route, r.fork ? forkKept : KEPT_PUBLIC)) {
                refused.add(route);
            }
        }
        return refused;
    }

    /**
     * The fork file lines that don't name one fork plugin's routes: a line must be {@code /rest/plugins/<id>} or below
     * it, {@code <id>} a plain id (letters, digits, {@code -}, {@code _}) that no core route uses. {@code /*} or
     * {@code /rest} would otherwise cover every fork route, and a core plugin's id would let a fork route hide among
     * core's.
     */
    static List<String> overBroad(List<String> forkDeclared, Scan scan) {
        Set<String> coreIds = new HashSet<>();
        for (Route r : scan.routes) {
            Matcher m = PLUGIN_ROUTE.matcher(stripWildcard(r.path));
            if (!r.fork && m.matches()) {
                coreIds.add(m.group(1));
            }
        }
        List<String> out = new ArrayList<>();
        for (String line : forkDeclared) {
            Matcher m = PLUGIN_ROUTE.matcher(stripWildcard(line));
            if (!m.matches() || coreIds.contains(m.group(1))) {
                out.add(line);
            }
        }
        return out;
    }

    private static String stripWildcard(String raw) {
        return raw.endsWith("/*") ? raw.substring(0, raw.length() - 2) : raw;
    }

    /** A synthetic repository: one fork plugin with a public route, and a fork file declaring {@code declared}. */
    private File tree(String... declared) throws IOException {
        File root = tmp.newFolder("repo");
        write(root, "plugins/fork/demo/src/main/java/com/mdmesh/demo/DemoRestModule.java",
                restModule("com.mdmesh.demo", "DemoRestModule", "/rest/plugins/demo/public/*"));
        StringBuilder file = new StringBuilder("# A fork's public routes, one per line.\n");
        for (String d : declared) {
            file.append(d).append('\n');
        }
        write(root, FORK_ROUTES, file.toString());
        return root;
    }

    private static String restModule(String pkg, String name, String publicRoute) {
        return "package " + pkg + ";\n\nimport java.util.Arrays;\nimport java.util.List;\n\npublic class " + name
                + " {\n    private static final List<String> publicResources = Arrays.asList(\"" + publicRoute
                + "\");\n}\n";
    }

    private static void write(File root, String path, String text) throws IOException {
        File file = new File(root, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    static boolean isPrivate(String route) {
        return Arrays.asList(route.split("/")).contains("private");
    }

    private static boolean covered(String route, List<String> kept) {
        for (String k : kept) {
            if (route.equals(k) || route.startsWith(k + "/")) {
                return true;
            }
        }
        return false;
    }

    static List<String> routesOf(Class<?> type) {
        List<String> out = new ArrayList<>();
        // By name: the servlet API on the server's compile classpath predates @WebServlet.
        for (Annotation a : type.getAnnotations()) {
            if (a.annotationType().getName().equals("javax.servlet.annotation.WebServlet")) {
                for (String attribute : new String[]{"urlPatterns", "value"}) {
                    try {
                        out.addAll(Arrays.asList((String[]) a.annotationType().getMethod(attribute).invoke(a)));
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError("read @WebServlet." + attribute + " of " + type, e);
                    }
                }
            }
        }
        ServerEndpoint endpoint = type.getAnnotation(ServerEndpoint.class);
        if (endpoint != null) {
            out.add(endpoint.value());
        }
        Path classPath = type.getAnnotation(Path.class);
        if (classPath != null) {
            String base = "/rest" + slash(classPath.value());
            for (Method m : type.getDeclaredMethods()) {
                Path methodPath = m.getAnnotation(Path.class);
                if (methodPath != null) {
                    out.add(base + slash(methodPath.value()));
                } else if (isHttpMethod(m)) {
                    out.add(base);
                }
            }
        }
        return out;
    }

    private static boolean isHttpMethod(Method m) {
        for (Annotation a : m.getAnnotations()) {
            if (a.annotationType().isAnnotationPresent(HttpMethod.class)) {
                return true;
            }
        }
        return false;
    }

    private static String slash(String p) {
        String s = p.trim();
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() || s.startsWith("/") ? s : "/" + s;
    }

    /** The source's top-level class: its package and its file name. */
    private static String className(File source, String text) {
        Matcher pkg = PACKAGE.matcher(text);
        assertTrue("package of " + source, pkg.find());
        return pkg.group(1) + "." + source.getName().replaceFirst("\\.java$", "");
    }

    private static List<File> sources(File rootDir) throws IOException {
        java.nio.file.Path root = rootDir.toPath();
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

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
