package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.extension.ConfigurationExtensionValidator;
import com.hmdm.extension.ConfigurationExtensions;
import com.hmdm.extension.DesiredConfigContributor;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.DesiredConfigBuilder;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** How the server plugins' extension points are gathered at start: the ids they run under, and what is logged. */
public class ExtensionPointsTest {

    private final List<String> warnings = new ArrayList<>();
    private AppenderSkeleton capture;

    @Before
    public void captureWarnings() {
        capture = new AppenderSkeleton() {
            @Override protected void append(LoggingEvent event) {
                if (event.getLevel().isGreaterOrEqual(Level.WARN)) warnings.add(event.getRenderedMessage());
            }
            @Override public void close() {}
            @Override public boolean requiresLayout() { return false; }
        };
        Logger.getLogger(ExtensionPoints.class).addAppender(capture);
    }

    @After
    public void releaseWarnings() {
        Logger.getLogger(ExtensionPoints.class).removeAppender(capture);
    }

    private static ConfigurationExtensionValidator validator(String id) {
        return new ConfigurationExtensionValidator() {
            @Override public String extensionId() { return id; }
            @Override public List<Problem> validate(String namespace, JsonNode value) { return null; }
        };
    }

    private static DesiredConfigContributor contributor(String id) {
        return new DesiredConfigContributor() {
            @Override public String pluginId() { return id; }
            @Override public Map<String, Boolean> policies(Configuration c, List<Application> apps) {
                return Collections.singletonMap("on", true);
            }
        };
    }

    /**
     * An id is looked up as a plugin's, so a validator or contributor under an id no installed plugin has (the plugin
     * "netaccess-server", its validator "netaccess") runs for no customer: the start says so.
     */
    @Test
    public void startWarnsAboutAnIdWithoutAnInstalledPlugin() {
        Set<String> installed = new LinkedHashSet<>(Collections.singletonList("netaccess-server"));
        new ExtensionPoints(new LinkedHashSet<>(Arrays.asList(validator("netaccess"), validator("netaccess-server"))),
                new LinkedHashSet<>(Collections.singletonList(contributor("netaccess"))),
                Fakes.pluginStatus((plugin, customer) -> !installed.contains(plugin)));
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).contains("validator \"netaccess\" runs for no customer"));
        assertTrue(warnings.get(0), warnings.get(0).contains("installed and on"));
        assertTrue(warnings.get(1), warnings.get(1).contains("contributor \"netaccess\" runs for no customer"));
    }

    @Test
    public void installedPluginsStartWithoutAWarning() {
        Fakes.extensionPoints(Collections.singletonList(validator("netaccess")),
                Collections.singletonList(contributor("netaccess")));
        assertEquals(Collections.emptyList(), warnings);
    }

    /**
     * The contributor runs under its start id through a wrapper, which must forward every method of the interface: a
     * default method added to DesiredConfigContributor (as its Javadoc plans for non-boolean settings) and not
     * forwarded would silently answer with the default instead of the plugin's own.
     */
    @Test
    public void theWrappersForwardEveryMethodOfTheInterfaces() throws Exception {
        ExtensionPoints points = Fakes.extensionPoints(Collections.singletonList(validator("netaccess")),
                Collections.singletonList(contributor("netaccess")));
        assertForwardsEveryMethod(DesiredConfigContributor.class, points.contributors(1).get(0));
        assertForwardsEveryMethod(ConfigurationExtensionValidator.class, points.validators(1).get(0));
    }

    /** Every instance method of the interface is the wrapper's own (an interface's static helpers have nothing to forward). */
    private static void assertForwardsEveryMethod(Class<?> spi, Object wrapped) throws Exception {
        for (Method m : spi.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Method impl = wrapped.getClass().getMethod(m.getName(), m.getParameterTypes());
            assertEquals(spi.getSimpleName() + "." + m.getName() + " is not forwarded", wrapped.getClass(), impl.getDeclaringClass());
        }
    }

    /** As a contributor's keys, a validator's namespaces are those of the id the gate checked at start. */
    @Test
    public void aValidatorRunsUnderTheIdItHadAtStart() throws Exception {
        List<String> asked = new ArrayList<>();
        ConfigurationExtensionValidator fickle = new ConfigurationExtensionValidator() {
            private int calls;
            @Override public String extensionId() { return calls++ == 0 ? "netaccess" : "other"; }
            @Override public List<Problem> validate(String namespace, JsonNode value) {
                asked.add(namespace);
                return null;
            }
        };
        ExtensionPoints points = Fakes.extensionPoints(Collections.singletonList(fickle),
                Collections.<DesiredConfigContributor>emptyList());
        ObjectNode sent = (ObjectNode) new ObjectMapper().readTree("{\"netaccess\":{},\"other\":{}}");
        for (int i = 0; i < 2; i++) ConfigurationExtensions.check(sent, null, points.validators(1));
        assertEquals(Arrays.asList("netaccess", "netaccess"), asked);
    }

    /** The keys go under the id the gate checked at start, even from a pluginId() that answers differently later. */
    @Test
    public void aContributorRunsUnderTheIdItHadAtStart() {
        DesiredConfigContributor fickle = new DesiredConfigContributor() {
            private int asked;
            @Override public String pluginId() { return asked++ == 0 ? "netaccess" : "other"; }
            @Override public Map<String, Boolean> policies(Configuration c, List<Application> apps) {
                return Collections.singletonMap("on", true);
            }
        };
        ExtensionPoints points = Fakes.extensionPoints(Collections.<ConfigurationExtensionValidator>emptyList(),
                Collections.singletonList(fickle));
        for (int i = 0; i < 2; i++) {
            Configuration cfg = new Configuration();
            cfg.setId(3);
            Set<String> keys = DesiredConfigBuilder.build(cfg, new ArrayList<>(), points.contributors(1)).getPolicies()
                    .keySet().stream().filter(k -> k.startsWith("x.")).collect(Collectors.toSet());
            assertEquals(Collections.singleton("x.netaccess.on"), keys);
        }
    }
}
