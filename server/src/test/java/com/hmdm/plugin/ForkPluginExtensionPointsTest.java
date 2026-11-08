package com.hmdm.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import com.google.inject.servlet.ServletModule;
import com.hmdm.extension.ConfigurationExtensionValidator;
import com.hmdm.extension.ConfigurationExtensions;
import com.hmdm.extension.DesiredConfigContributor;
import com.hmdm.extension.ExtensionError;
import com.hmdm.guice.module.ExtensionPointsModule;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.plugin.persistence.domain.DisabledPlugin;
import com.hmdm.plugin.persistence.domain.Plugin;
import com.hmdm.plugin.persistence.mapper.PluginMapper;
import com.hmdm.plugin.service.PluginStatusCache;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.rest.resource.support.ExtensionPoints;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import com.hmdm.util.DesiredConfigBuilder;
import com.mdmesh.fixture.FixturePluginConfiguration;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A fork plugin under com.mdmesh is found, and what its Guice module binds reaches the configuration save's checks
 * and the config.apply document, next to core's (empty) extension points, for the customers it is on for.
 */
public class ForkPluginExtensionPointsTest {

    private static final Key<Set<ConfigurationExtensionValidator>> VALIDATORS =
            Key.get(new TypeLiteral<Set<ConfigurationExtensionValidator>>() {});
    private static final Key<Set<DesiredConfigContributor>> CONTRIBUTORS =
            Key.get(new TypeLiteral<Set<DesiredConfigContributor>>() {});

    private static Injector withFixture() {
        List<Module> modules = new ArrayList<>(new FixturePluginConfiguration().getPluginModules(null));
        // Its REST filters belong to the servlet container: Guice's servlet module needs the --add-opens Tomcat runs
        // with.
        modules.removeIf(m -> m instanceof ServletModule);
        modules.add(new ExtensionPointsModule());
        return Guice.createInjector(modules);
    }

    private static final int ON = 1, OFF = 2;

    /** The fixture plugin's validator and contributor, the plugin on for customer ON and turned off for OFF. */
    private static ExtensionPoints fixturePoints() {
        Injector injector = withFixture();
        Plugin row = new Plugin();
        row.setId(7);
        row.setIdentifier(FixturePluginConfiguration.ID);
        DisabledPlugin off = new DisabledPlugin();
        off.setCustomerId(OFF);
        off.setPluginId(7);
        FakeMapper<PluginMapper> plugins = new FakeMapper<>(PluginMapper.class)
                .answer("findRegisteredPlugins", a -> Fakes.list(row))
                .answer("getDisabledPluginsForAllCustomers", a -> Fakes.list(off));
        return new ExtensionPoints(injector.getInstance(VALIDATORS), injector.getInstance(CONTRIBUTORS),
                new PluginStatusCache(plugins.mapper));
    }

    @BeforeClass
    public static void scan() {
        PluginList.init(PluginDiscoveryTest.context());
    }

    /** Answers one configuration and no apps, as the reconciler reads them. */
    private static final class OneConfiguration extends UnsecureDAO {
        final Configuration configuration;

        OneConfiguration(Configuration configuration) {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, "/tmp", 0, "x");
            this.configuration = configuration;
        }

        @Override public Configuration getConfigurationById(Integer id) { return configuration; }
        @Override public List<Application> getPlainConfigurationAppsOptimized(Integer id) { return new ArrayList<>(); }
    }

    private static Configuration configuration(int customerId, String extensions) throws Exception {
        Configuration c = new Configuration();
        c.setId(3);
        c.setCustomerId(customerId);
        c.setExtensions((ObjectNode) new ObjectMapper().readTree(extensions));
        return c;
    }

    private static Device device() {
        Device device = new Device();
        device.setConfigurationId(3);
        device.setNumber("WH-1");
        return device;
    }

    @Test
    public void theScanFindsAPluginUnderComMdmesh() {
        assertTrue(PluginList.getEnabledPlugins().toString(), PluginList.isPluginEnabled(FixturePluginConfiguration.ID));
    }

    @Test
    public void coreBindsNoValidatorOrContributor() {
        Injector core = Guice.createInjector(new ExtensionPointsModule());
        assertTrue(core.getInstance(VALIDATORS).isEmpty());
        assertTrue(core.getInstance(CONTRIBUTORS).isEmpty());
    }

    @Test
    public void thePluginsValidatorRefusesTheSave() throws Exception {
        List<ConfigurationExtensionValidator> validators = fixturePoints().validators(ON);
        List<ExtensionError> errors = ConfigurationExtensions.check(
                configuration(ON, "{\"fixture.lock\":{\"on\":\"yes\"}}").getExtensions(), validators);
        assertEquals(1, errors.size());
        assertEquals("error.extension.fixture.lock.on", errors.get(0).getKey());
        assertTrue(ConfigurationExtensions.check(configuration(ON, "{\"fixture.lock\":{\"on\":true}}").getExtensions(),
                validators).isEmpty());
    }

    @Test
    public void thePluginsContributorAddsItsKeyToTheDocumentTheReconcilerBuilds() throws Exception {
        Configuration on = configuration(ON, "{\"fixture.lock\":{\"on\":true}}");
        DesiredConfig d = new ConfigReconciler(new OneConfiguration(on), null, fixturePoints()).currentDocument(device());
        assertEquals(Boolean.TRUE, d.getPolicies().get("x.fixture.lock"));

        // Without its data the plugin adds nothing: the revision is the one core alone gives.
        Configuration none = configuration(ON, "{}");
        DesiredConfig plain = new ConfigReconciler(new OneConfiguration(none), null, fixturePoints()).currentDocument(device());
        assertFalse(plain.getPolicies().containsKey("x.fixture.lock"));
        assertEquals(coreRevision(none), plain.getRevision());
    }

    private static String coreRevision(Configuration c) {
        return DesiredConfigBuilder.build(c, new ArrayList<Application>(), Collections.<DesiredConfigContributor>emptyList())
                .getRevision();
    }

    /**
     * A plugin turned off for a customer checks none of its saves and adds no keys to its configurations' documents, so
     * turning it off drops the keys (the agent then releases them) and the revision is core's alone.
     */
    @Test
    public void aPluginTurnedOffForTheCustomerNeitherChecksNorContributes() throws Exception {
        ExtensionPoints points = fixturePoints();
        assertEquals(Collections.emptyList(), points.validators(OFF));
        assertEquals(Collections.emptyList(), ConfigurationExtensions.check(
                configuration(OFF, "{\"fixture.lock\":{\"on\":\"yes\"}}").getExtensions(), points.validators(OFF)));

        Configuration off = configuration(OFF, "{\"fixture.lock\":{\"on\":true}}");
        DesiredConfig d = new ConfigReconciler(new OneConfiguration(off), null, points).currentDocument(device());
        assertFalse(d.getPolicies().containsKey("x.fixture.lock"));
        assertEquals(coreRevision(off), d.getRevision());

        // The same data on a configuration of a customer it is on for: checked, and sent.
        assertEquals(1, points.validators(ON).size());
        Configuration on = configuration(ON, "{\"fixture.lock\":{\"on\":true}}");
        assertEquals(Boolean.TRUE, new ConfigReconciler(new OneConfiguration(on), null, points)
                .currentDocument(device()).getPolicies().get("x.fixture.lock"));
    }

    private static DesiredConfigContributor contributor(String pluginId, Throwable failure) {
        return new DesiredConfigContributor() {
            @Override public String pluginId() { return pluginId; }
            @Override public Map<String, Boolean> policies(Configuration c, List<Application> apps) {
                if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                if (failure instanceof Error) throw (Error) failure;
                return null;
            }
        };
    }

    /**
     * One broken plugin, even one whose classes are missing from the bundle (NoClassDefFoundError), stops config.apply
     * for its configurations only: no revision for the console (shown out of sync), nothing queued, and the check-in
     * itself goes on.
     */
    @Test
    public void aContributorThatThrowsLeavesTheStatusWithoutARevisionAndConfigApplyUnsent() throws Exception {
        for (Throwable failure : Arrays.<Throwable>asList(new NullPointerException(), new NoClassDefFoundError("org/example/Vpn"))) {
            ConfigReconciler reconciler = new ConfigReconciler(new OneConfiguration(configuration(ON, "{}")), null,
                    Fakes.extensionPoints(Collections.<ConfigurationExtensionValidator>emptySet(),
                            Collections.singleton(contributor("broken", failure))));
            assertNull(failure.toString(), reconciler.currentRevision(device()));
            assertFalse(failure.toString(), reconciler.reconcile(device(),
                    Collections.singleton(DesiredConfigBuilder.CAPABILITY), "old", "0.3.7", "0.3.7", 1L, 1L));
        }
    }

    @Test(expected = IllegalStateException.class)
    public void aBadPluginIdFailsAtStart() {
        Fakes.extensionPoints(Collections.<ConfigurationExtensionValidator>emptySet(),
                Collections.singleton(contributor("Net Access", null)));
    }

    @Test(expected = IllegalStateException.class)
    public void aValidatorWithABadExtensionIdFailsAtStart() {
        ConfigurationExtensionValidator bad = new ConfigurationExtensionValidator() {
            @Override public String extensionId() { return "NetAccess"; }
            @Override public List<Problem> validate(String namespace, JsonNode value) { return null; }
        };
        Fakes.extensionPoints(Collections.singleton(bad), Collections.<DesiredConfigContributor>emptySet());
    }
}
