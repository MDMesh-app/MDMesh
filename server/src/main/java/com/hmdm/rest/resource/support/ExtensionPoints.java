package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdm.extension.ConfigurationExtensionValidator;
import com.hmdm.extension.ConfigurationExtensions;
import com.hmdm.extension.DesiredConfigContributor;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.plugin.service.PluginStatusCache;
import com.hmdm.util.DesiredConfigBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The server plugins' extension points (the Guice sets ExtensionPointsModule declares), as one customer gets them: a
 * plugin turned off for the customer (PluginStatusCache, as PluginAccessFilter answers 404 for its private REST)
 * checks none of that customer's saves and adds no policy keys to its configurations' documents. A plugin without a
 * {@code plugins} row is off for every customer.
 *
 * <p>So turning a plugin off for a customer drops its keys from the documents of that customer's configurations: their
 * revisions change, the devices get config.apply again, and the agent releases what those keys managed, as for any key
 * a document stops sending. Turning it back on sends them again.</p>
 *
 * <p>The plugins' state is cached (PluginStatusCache reads a customer's once), so a check-in costs no query.</p>
 */
@Singleton
public class ExtensionPoints {
    private static final Logger logger = LoggerFactory.getLogger(ExtensionPoints.class);

    private final List<ConfigurationExtensionValidator> validators;
    private final List<String> validatorIds = new ArrayList<String>();
    private final List<DesiredConfigContributor> contributors;
    private final List<String> contributorIds = new ArrayList<String>();
    private final PluginStatusCache pluginStatus;

    /**
     * @throws IllegalStateException for an extensionId or a pluginId that isn't lowercase kebab-case: it would never
     *                               match a namespace or a plugin, so the server fails to start instead
     */
    @Inject
    public ExtensionPoints(Set<ConfigurationExtensionValidator> validators, Set<DesiredConfigContributor> contributors,
                           PluginStatusCache pluginStatus) {
        List<ConfigurationExtensionValidator> validatorsWithIds = new ArrayList<ConfigurationExtensionValidator>(validators.size());
        for (ConfigurationExtensionValidator v : validators) {
            String id = v.extensionId();
            if (!ConfigurationExtensions.isId(id)) {
                throw new IllegalStateException("ConfigurationExtensionValidator " + v.getClass().getName()
                        + ": extensionId \"" + id + "\" is not lowercase kebab-case of at most 32 characters");
            }
            validatorIds.add(id);
            validatorsWithIds.add(withExtensionId(id, v));
        }
        List<DesiredConfigContributor> withIds = new ArrayList<DesiredConfigContributor>(contributors.size());
        for (DesiredConfigContributor c : contributors) {
            String id = DesiredConfigBuilder.checkedPluginId(c);
            contributorIds.add(id);
            withIds.add(withPluginId(id, c));
        }
        this.validators = Collections.unmodifiableList(validatorsWithIds);
        this.contributors = Collections.unmodifiableList(withIds);
        this.pluginStatus = pluginStatus;
        // A fork's plugin missing here is missing from plugins/fork/bundle/pom.xml, or outside com.hmdm and com.mdmesh.
        logger.info("Server plugins' extension points: validators {}, contributors {}", validatorIds, contributorIds);
        warnWithoutPlugin("validator", validatorIds);
        warnWithoutPlugin("contributor", contributorIds);
    }

    /**
     * The gate looks each id up as a plugin's, so an id that isn't one (a validator "netaccess" in a plugin
     * "netaccess-server") runs for no customer. A warning, not a failed start: the plugin's REST is off too then.
     */
    private void warnWithoutPlugin(String what, List<String> ids) {
        for (String id : ids) {
            if (pluginStatus.isPluginDisabled(id, null)) {
                logger.warn("The {} \"{}\" runs for no customer: no plugin with this id is installed and on (a "
                        + "PluginConfiguration, and a plugins row that isn't disabled)", what, id);
            }
        }
    }

    /**
     * The contributor under the id it had at start, so the keys always go under the id the gate checked, even for a
     * pluginId() that doesn't answer the same each time. It must forward every method of the interface: a default
     * method added there needs a line here (ExtensionPointsTest fails until it has one).
     */
    private static DesiredConfigContributor withPluginId(String id, DesiredConfigContributor c) {
        return new DesiredConfigContributor() {
            @Override public String pluginId() { return id; }
            @Override public Map<String, Boolean> policies(Configuration configuration, List<Application> applications) {
                return c.policies(configuration, applications);
            }
            @Override public String toString() { return c.toString(); }
        };
    }

    /** The validator under the id it had at start, as {@link #withPluginId} for a contributor (same test). */
    private static ConfigurationExtensionValidator withExtensionId(String id, ConfigurationExtensionValidator v) {
        return new ConfigurationExtensionValidator() {
            @Override public String extensionId() { return id; }
            @Override public List<Problem> validate(String namespace, JsonNode value) { return v.validate(namespace, value); }
            @Override public String toString() { return v.toString(); }
        };
    }

    /** The validators of the plugins on for the customer (null: only installed). */
    public List<ConfigurationExtensionValidator> validators(Integer customerId) {
        return on(validators, validatorIds, customerId);
    }

    /** The contributors of the plugins on for the customer. */
    public List<DesiredConfigContributor> contributors(Integer customerId) {
        return on(contributors, contributorIds, customerId);
    }

    /** The items whose plugin (ids.get(i) for all.get(i)) is on for the customer. */
    private <T> List<T> on(List<T> all, List<String> ids, Integer customerId) {
        if (all.isEmpty()) return all;
        List<T> on = new ArrayList<T>(all.size());
        for (int i = 0; i < all.size(); i++) {
            if (!pluginStatus.isPluginDisabled(ids.get(i), customerId)) on.add(all.get(i));
        }
        return on;
    }
}
