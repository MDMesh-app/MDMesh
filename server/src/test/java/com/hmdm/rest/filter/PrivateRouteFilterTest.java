package com.hmdm.rest.filter;

import com.example.brokenplugin.BrokenPluginConfiguration;
import com.google.inject.Binding;
import com.google.inject.Module;
import com.google.inject.servlet.InstanceFilterBinding;
import com.google.inject.servlet.InstanceServletBinding;
import com.google.inject.servlet.LinkedFilterBinding;
import com.google.inject.servlet.LinkedServletBinding;
import com.google.inject.servlet.ServletModuleBinding;
import com.google.inject.servlet.ServletModule;
import com.google.inject.servlet.ServletModuleTargetVisitor;
import com.google.inject.servlet.UriPatternType;
import com.google.inject.spi.DefaultBindingTargetVisitor;
import com.google.inject.spi.Element;
import com.google.inject.spi.ElementSource;
import com.google.inject.spi.Elements;
import com.google.inject.spi.Message;
import com.hmdm.HMDMApplication;
import com.hmdm.guice.module.MainRestModule;
import com.hmdm.guice.module.PrivateRestModule;
import com.hmdm.guice.module.PublicRestModule;
import com.hmdm.notification.guice.module.NotificationRestModule;
import com.hmdm.plugin.PluginDiscoveryTest;
import com.hmdm.plugin.PluginList;
import com.hmdm.plugin.guice.module.PluginRestModule;
import com.hmdm.plugin.rest.PluginAccessFilter;
import com.mdmesh.fixture.FixturePrivateResource;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import org.junit.Test;

import javax.ws.rs.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link PublicRouteInventoryTest} lets a route below a {@code private} segment through as session-checked. That holds
 * only if a filter mapping puts the route through {@link AuthFilter}, and a plugin's routes (below
 * {@code /rest/plugins/<id>/}) through {@link PluginAccessFilter} after it, which reads the signed-in user's customer
 * to apply that customer's disable list. Core maps {@code /rest/private/*} itself, but each plugin, core's or a fork's,
 * maps its own private routes in its {@code ServletModule}; a typo there serves the route to anyone.
 *
 * <p>This reads the mappings the way Guice's servlet pipeline will use them: {@link Elements#getElements} records what
 * core's servlet modules and every plugin's {@code ServletModule} from the plugin scan ({@link PluginList}, which finds
 * the fixture plugin and a fork's plugins on this classpath) bind, in the order Initializer installs them, and Guice's
 * servlet SPI ({@link ServletModuleTargetVisitor}) turns each filter and servlet binding back into its filter class and
 * pattern. Nothing is injected and no filter is created. {@link ServletModuleBinding#matchesUri} is Guice's own
 * matcher, so the chain worked out for a route is the chain the pipeline runs, in its order. The routes are every
 * {@code @Path} route of the classes Jersey serves ({@link HMDMApplication#getClasses()}) and every servlet a module
 * serves, that sit below a {@code private} segment.</p>
 */
public class PrivateRouteFilterTest {

    /** Core's servlet modules, in Initializer.getModules()'s order; the plugin modules follow them. */
    private static List<Module> coreModules() {
        return Arrays.asList(new MainRestModule(), new PublicRestModule(), new PrivateRestModule(),
                new NotificationRestModule(), new PluginRestModule());
    }

    /**
     * The ServletModules the server installs: core's, then each plugin's from the plugin scan, in Initializer's order.
     * Only a ServletModule maps filters and servlets (filter() and serve() are its API), and the others' configure()
     * needs the server: a Liquibase module migrates the database there, a persistence module reads the JDBC settings.
     *
     * <p>Both route tests check only these modules' servlets and filters, so this fails unless every concrete
     * {@code ServletModule} class under {@link PluginList#PACKAGES} is the class of one of them: one installed from
     * inside another module, or a new core module {@link #coreModules()} doesn't list, would be served unchecked.
     * This module's own test classes are left out (see {@link #uninstalled}).</p>
     */
    static List<Module> servletModules() {
        PluginList.init(PluginDiscoveryTest.context());
        List<Module> modules = new ArrayList<>(coreModules());
        PluginList.getPluginModules().stream().filter(m -> m instanceof ServletModule).forEach(modules::add);

        Map<String, Class<?>> found = servletModuleClasses(PluginList.PACKAGES);
        Set<String> notFound = new TreeSet<>(classNames(modules));
        notFound.removeAll(found.keySet());
        assertEquals("installed ServletModules the class scan didn't find", new TreeSet<String>(), notFound);
        assertEquals("ServletModules the server doesn't install on their own: return a plugin's from "
                + "getPluginModules() itself, not from inside another module; add core's to "
                + "PrivateRouteFilterTest.coreModules()", new TreeSet<String>(), uninstalled(found, modules, true));
        return modules;
    }

    /**
     * Every concrete {@code ServletModule} class under {@code packages}, by name. ClassGraph reads the class files
     * there, in directories and jars, with nested and anonymous classes (which aren't public, hence
     * {@code ignoreClassVisibility}); it also reads each one's superclasses outside those packages, so a subclass of
     * another ServletModule subclass, a library's included, is found. Each class is loaded, not initialized.
     */
    static Map<String, Class<?>> servletModuleClasses(List<String> packages) {
        Map<String, Class<?>> out = new TreeMap<>();
        try (ScanResult scan = new ClassGraph().enableClassInfo().ignoreClassVisibility()
                .acceptPackages(packages.toArray(new String[0])).scan()) {
            for (ClassInfo type : scan.getSubclasses(ServletModule.class.getName())) {
                if (!type.isAbstract()) {
                    try {
                        out.put(type.getName(), Class.forName(type.getName(), false,
                                PrivateRouteFilterTest.class.getClassLoader()));
                    } catch (ClassNotFoundException e) {
                        throw new AssertionError("load " + type.getName() + ", which the class scan found", e);
                    }
                }
            }
        }
        return out;
    }

    /**
     * The classes in {@code found} that aren't the class of one of {@code installed}. With {@code skipTests}, those
     * compiled from this module's tests are left out: launcher.war never contains them, and a test builds and installs
     * the ones it needs by hand ({@link PublicRouteInventoryTest#isThisModulesTest}).
     */
    static TreeSet<String> uninstalled(Map<String, Class<?>> found, List<Module> installed, boolean skipTests) {
        TreeSet<String> out = new TreeSet<>(found.keySet());
        out.removeAll(classNames(installed));
        if (skipTests) {
            out.removeIf(name -> PublicRouteInventoryTest.isThisModulesTest(found.get(name)));
        }
        return out;
    }

    private static Set<String> classNames(List<Module> modules) {
        return modules.stream().map(m -> m.getClass().getName()).collect(Collectors.toSet());
    }

    @Test
    public void everyPrivateRouteGoesThroughAuthFilterAndAPluginsAlsoThroughPluginAccessFilter() {
        List<Module> modules = servletModules();
        Set<Class<?>> resources = new HMDMApplication(null).getClasses().stream()
                .filter(c -> c.isAnnotationPresent(Path.class)).collect(Collectors.toSet());
        assertTrue("the fixture plugin's private resource is served", resources.contains(FixturePrivateResource.class));

        Mappings mappings = mappings(modules);
        assertTrue("the visitor read the filter mappings (" + mappings.filters.size() + ")",
                mappings.filters.size() > 20);
        assertTrue("the scan saw private plugin routes", privateRoutes(resources, mappings).stream()
                .anyMatch(r -> r.startsWith("/rest/plugins/fixture/private/")));

        assertEquals("private routes whose filters are missing, typo'd or out of order (map them in the plugin's "
                + "ServletModule as web/EXTENDING.md's REST row shows)", new TreeSet<String>(),
                unchecked(resources, mappings));
    }

    @Test
    public void aPluginThatMapsItsPrivateRoutesWronglyIsRefused() {
        List<Module> modules = new ArrayList<>(coreModules());
        modules.addAll(new BrokenPluginConfiguration().getPluginModules(null));

        assertEquals(new TreeSet<>(Arrays.asList(
                "/rest/plugins/broken/private/typo/{id}: not through AuthFilter",
                "/rest/plugins/broken/private/typo/{id}: not through PluginAccessFilter",
                "/rest/plugins/broken/private/noauth: not through AuthFilter",
                "/rest/plugins/broken/private/noaccess: not through PluginAccessFilter",
                "/rest/plugins/broken/private/reversed: PluginAccessFilter runs before AuthFilter",
                "/rest/plugins/broken/private/upload: not through AuthFilter",
                "/rest/plugins/broken/private/upload: not through PluginAccessFilter")),
                unchecked(Collections.<Class<?>>singleton(BrokenPluginConfiguration.BrokenResource.class),
                        mappings(modules)));
    }

    @Test
    public void aRegexServletFailsTheCheck() {
        List<Module> modules = new ArrayList<>(coreModules());
        modules.add(new BrokenPluginConfiguration.RegexRestModule());

        AssertionError e = assertThrows(AssertionError.class, () -> mappings(modules));
        assertTrue(e.getMessage(), e.getMessage().contains("serves /rest/plugins/broken/private/regex/.* by a regex"));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** A filter mapping: the filter and the binding that holds its pattern. */
    static final class FilterMapping {
        final Class<?> filter;
        final ServletModuleBinding binding;

        FilterMapping(Class<?> filter, ServletModuleBinding binding) {
            this.filter = filter;
            this.binding = binding;
        }
    }

    /** A servlet mapping: the binding that holds its pattern, and the module that served it. */
    static final class ServletMapping {
        final ServletModuleBinding binding;
        /** The class name of the module whose {@code serve(...)} made the binding. */
        final String module;

        ServletMapping(ServletModuleBinding binding, String module) {
            this.binding = binding;
            this.module = module;
        }
    }

    static final class Mappings {
        final List<FilterMapping> filters = new ArrayList<>();
        final List<ServletMapping> servlets = new ArrayList<>();

        /** The filters a request for {@code uri} passes through, in the pipeline's order. */
        List<Class<?>> chain(String uri) {
            return filters.stream().filter(m -> m.binding.matchesUri(uri)).map(m -> m.filter)
                    .collect(Collectors.toList());
        }
    }

    /** The filter and servlet mappings the modules bind, in binding order (the order Guice's pipeline runs them). */
    static Mappings mappings(List<Module> modules) {
        Mappings out = new Mappings();
        Visitor visitor = new Visitor(out);
        for (Element element : Elements.getElements(modules)) {
            if (element instanceof Message && !inGuicesServletModule((Message) element)) {
                Message m = (Message) element;
                throw new AssertionError("a module failed to configure, at " + m.getSource() + ": " + m.getMessage(),
                        m.getCause());
            }
            if (element instanceof Binding) {
                visitor.module = definingModule(element.getSource());
                ((Binding<?>) element).acceptTargetVisitor(visitor);
            }
        }
        return out;
    }

    /**
     * Whether the error comes from Guice's own InternalServletModule, which every ServletModule installs: its provider
     * methods need the --add-opens Tomcat runs with and these tests don't. It maps nothing, so the mappings are whole.
     */
    private static boolean inGuicesServletModule(Message message) {
        for (Object source : message.getSources()) {
            if (source instanceof ElementSource
                    && "com.google.inject.servlet.InternalServletModule".equals(definingModule(source))) {
                return true;
            }
        }
        return false;
    }

    /** The class name of the module that defined an element: the first of its source's module class names. */
    private static String definingModule(Object source) {
        if (!(source instanceof ElementSource)) {
            throw new AssertionError("an element's source is not an ElementSource: " + source);
        }
        return ((ElementSource) source).getModuleClassNames().get(0);
    }

    private static final class Visitor extends DefaultBindingTargetVisitor<Object, Void>
            implements ServletModuleTargetVisitor<Object, Void> {
        private final Mappings out;
        /** The module that defined the binding being visited. */
        String module;

        Visitor(Mappings out) {
            this.out = out;
        }

        @Override
        public Void visit(LinkedFilterBinding binding) {
            out.filters.add(new FilterMapping(binding.getLinkedKey().getTypeLiteral().getRawType(), binding));
            return null;
        }

        @Override
        public Void visit(InstanceFilterBinding binding) {
            out.filters.add(new FilterMapping(binding.getFilterInstance().getClass(), binding));
            return null;
        }

        @Override
        public Void visit(LinkedServletBinding binding) {
            return servlet(binding);
        }

        @Override
        public Void visit(InstanceServletBinding binding) {
            return servlet(binding);
        }

        /** A regex servlet has no one route to check, so it fails here, for this test and the public inventory. */
        private Void servlet(ServletModuleBinding binding) {
            assertEquals(module + " serves " + binding.getPattern() + " by a regex: serve a servlet-style pattern "
                    + "(serve(...), not serveRegex(...)), which the route checks can match", UriPatternType.SERVLET,
                    binding.getUriPatternType());
            out.servlets.add(new ServletMapping(binding, module));
            return null;
        }
    }

    /** The routes below a private segment: the resources' {@code @Path} routes and the servlets' patterns. */
    private static List<String> privateRoutes(Collection<Class<?>> resources, Mappings mappings) {
        List<String> routes = new ArrayList<>();
        for (Class<?> resource : resources) {
            routes.addAll(PublicRouteInventoryTest.routesOf(resource));
        }
        for (ServletMapping servlet : mappings.servlets) {
            routes.add(servlet.binding.getPattern());
        }
        return routes.stream().filter(PublicRouteInventoryTest::isPrivate).distinct().collect(Collectors.toList());
    }

    /** Each private route's missing or misordered filter, as {@code "<route>: <what is wrong>"}. */
    static TreeSet<String> unchecked(Collection<Class<?>> resources, Mappings mappings) {
        TreeSet<String> out = new TreeSet<>();
        for (String route : privateRoutes(resources, mappings)) {
            List<Class<?>> chain = mappings.chain(sampleUri(route));
            int auth = chain.indexOf(AuthFilter.class);
            if (auth < 0) {
                out.add(route + ": not through AuthFilter");
            }
            if (route.startsWith("/rest/plugins/")) {
                int access = chain.indexOf(PluginAccessFilter.class);
                if (access < 0) {
                    out.add(route + ": not through PluginAccessFilter");
                } else if (auth >= 0 && access < auth) {
                    out.add(route + ": PluginAccessFilter runs before AuthFilter");
                }
            }
        }
        return out;
    }

    /**
     * A request path for {@code route}: each {@code {param}} (with any regex inside it) becomes one segment, and a
     * servlet pattern's trailing {@code *} is dropped.
     */
    static String sampleUri(String route) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (char c : route.toCharArray()) {
            if (c == '{') {
                if (depth++ == 0) {
                    out.append('1');
                }
            } else if (c == '}') {
                depth--;
            } else if (depth == 0) {
                out.append(c);
            }
        }
        String uri = out.toString();
        return uri.endsWith("*") ? uri.substring(0, uri.length() - 1) : uri;
    }
}
