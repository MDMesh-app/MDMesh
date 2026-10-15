package com.hmdm.rest.filter;

import org.junit.Test;
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
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards the set of routes reachable without a console session. Every JAX-RS route ({@code @Path} on any class under
 * {@code com.hmdm}, which Jersey scans as a whole), servlet ({@code @WebServlet}), WebSocket endpoint
 * ({@code @ServerEndpoint}) and plugin {@code publicResources} registration in the source tree must either sit below a
 * {@code private} segment (session-checked), be switched off by {@link LegacyDeviceApiFilter}, or be listed in
 * {@link #KEPT_PUBLIC} below. A new public route therefore fails this test until someone decides which it is.
 * Also pins that the filter is the first filter in {@code web.xml}, ahead of the Guice filter.
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
            "/rest/public/signup",              // signup (off unless configured)
            "/rest/public/name",                // health / branding
            "/rest/public/logo",                // branding
            "/rest/plugin/main/public/registered", // plugin list for the console shell
            "/rest/plugins/messaging/{id}"      // console delete; checks the session's permission in the resource
    );

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern PUBLIC_RESOURCES = Pattern.compile(
            "publicResources\\s*=\\s*Arrays\\.asList\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern SERVE = Pattern.compile("\\.serve\\(\\s*\"([^\"]+)\"");
    private static final Pattern STRING = Pattern.compile("\"([^\"]+)\"");

    @Test
    public void everyPublicRouteIsLegacyOrDeliberatelyKept() throws Exception {
        List<String> routes = new ArrayList<>();
        int classes = 0;
        for (File source : sources()) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            if (text.contains("@Path(") || text.contains("@WebServlet(") || text.contains("@ServerEndpoint(")) {
                classes++;
                routes.addAll(routesOf(load(source, text)));
            }
            Matcher m = PUBLIC_RESOURCES.matcher(text);
            while (m.find()) {
                Matcher s = STRING.matcher(m.group(1));
                while (s.find()) {
                    routes.add(s.group(1));
                }
            }
            Matcher serve = SERVE.matcher(text);
            while (serve.find()) {
                routes.add(serve.group(1));
            }
        }
        assertTrue("the scan found the REST resources (" + classes + ")", classes > 30);
        assertTrue("the scan saw the agent API", routes.contains("/rest/public/agent/v1/checkin"));
        assertTrue("the scan saw the legacy sync API", routes.contains("/rest/public/sync/info"));
        assertTrue("the scan saw the legacy launcher QR", routes.contains("/rest/public/qr/json/{id}"));

        TreeSet<String> uncovered = new TreeSet<>();
        for (String raw : routes) {
            String route = raw.endsWith("/*") ? raw.substring(0, raw.length() - 2) : raw;
            if (!isPrivate(route) && !LegacyDeviceApiFilter.isLegacyPath(route) && !kept(route)) {
                uncovered.add(route);
            }
        }
        assertEquals("public routes that are neither legacy (LegacyDeviceApiFilter.LEGACY_PREFIXES) nor kept "
                + "(PublicRouteInventoryTest.KEPT_PUBLIC)", new TreeSet<String>(), uncovered);
    }

    @Test
    public void theFilterIsMappedFirstInWebXml() throws Exception {
        File webXml = new File(repoRoot(), "server/src/main/webapp/WEB-INF/web.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Document doc = factory.newDocumentBuilder().parse(webXml);

        NodeList filters = doc.getElementsByTagName("filter");
        String filterName = null;
        for (int i = 0; i < filters.getLength(); i++) {
            Element f = (Element) filters.item(i);
            if (text(f, "filter-class").equals(LegacyDeviceApiFilter.class.getName())) {
                filterName = text(f, "filter-name");
            }
        }
        assertTrue("web.xml declares LegacyDeviceApiFilter", filterName != null);

        Element first = (Element) doc.getElementsByTagName("filter-mapping").item(0);
        assertEquals(filterName, text(first, "filter-name"));
        assertEquals("/*", text(first, "url-pattern"));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static boolean isPrivate(String route) {
        return Arrays.asList(route.split("/")).contains("private");
    }

    private static boolean kept(String route) {
        for (String k : KEPT_PUBLIC) {
            if (route.equals(k) || route.startsWith(k + "/")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> routesOf(Class<?> type) {
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

    private static Class<?> load(File source, String text) throws ClassNotFoundException {
        Matcher pkg = PACKAGE.matcher(text);
        assertTrue("package of " + source, pkg.find());
        String name = source.getName().replaceFirst("\\.java$", "");
        return Class.forName(pkg.group(1) + "." + name, false, PublicRouteInventoryTest.class.getClassLoader());
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

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
