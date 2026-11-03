package com.mdmesh.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.multibindings.Multibinder;
import com.hmdm.extension.ConfigurationExtensionValidator;
import com.hmdm.extension.DesiredConfigContributor;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.plugin.PluginConfiguration;

import javax.servlet.ServletContext;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A fork plugin as web/EXTENDING.md describes one, in tests only: under com.mdmesh, which PluginList scans too,
 * it checks its namespace's data and adds one policy key from it, and filters its REST as the guide says.
 * ForkPluginExtensionPointsTest and PrivateRouteFilterTest use it.
 */
public class FixturePluginConfiguration implements PluginConfiguration {

    public static final String ID = "fixture";

    @Override
    public String getPluginId() {
        return ID;
    }

    @Override
    public String getRootPackage() {
        return "com.mdmesh.fixture";
    }

    @Override
    public List<Module> getPluginModules(ServletContext context) {
        return Arrays.<Module>asList(new FixtureRestModule(), new AbstractModule() {
            @Override
            protected void configure() {
                // Instances, not .to(Validator.class): Guice 4.2 makes a constructor class for .to(), which on JDK 17
                // needs the --add-opens that Tomcat runs with and these tests do not.
                Multibinder.newSetBinder(binder(), ConfigurationExtensionValidator.class).addBinding().toInstance(new Validator());
                Multibinder.newSetBinder(binder(), DesiredConfigContributor.class).addBinding().toInstance(new Contributor());
            }
        });
    }

    /** {@code fixture.lock} must say {@code on} as true or false. */
    public static class Validator implements ConfigurationExtensionValidator {
        @Override public String extensionId() { return ID; }

        @Override public List<Problem> validate(String namespace, JsonNode value) {
            if (!"fixture.lock".equals(namespace) || value.path("on").isBoolean()) return null;
            return Collections.singletonList(new Problem("on", "Say whether the lock is on."));
        }
    }

    /** Sends {@code fixture.lock}'s {@code on} as {@code x.fixture.lock}. */
    public static class Contributor implements DesiredConfigContributor {
        @Override public String pluginId() { return ID; }

        @Override public Map<String, Boolean> policies(Configuration configuration, List<Application> applications) {
            JsonNode on = configuration.getExtensions() == null ? null : configuration.getExtensions().path("fixture.lock").get("on");
            return on == null || !on.isBoolean() ? null : Collections.singletonMap("lock", on.asBoolean());
        }
    }
}
