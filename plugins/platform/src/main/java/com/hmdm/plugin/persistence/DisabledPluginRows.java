package com.hmdm.plugin.persistence;

import com.google.inject.Inject;
import com.hmdm.plugin.persistence.domain.DisabledPlugin;
import com.hmdm.plugin.persistence.mapper.PluginMapper;
import org.mybatis.guice.transactional.Transactional;

import java.util.List;

/**
 * <p>The writes of a customer's disabled plugins (<code>pluginsDisabled</code>), each in its own transaction. Only
 * {@link PluginDAO} calls these, from methods that are not <code>@Transactional</code>, so that it can drop the
 * customer's cached state (PluginStatusCache) once the transaction has ended. Dropping it inside the transaction,
 * before the commit, would let a concurrent read cache the old rows again.</p>
 */
public class DisabledPluginRows {

    private final PluginMapper pluginMapper;

    @Inject
    public DisabledPluginRows(PluginMapper pluginMapper) {
        this.pluginMapper = pluginMapper;
    }

    /**
     * Replaces the customer's list with these plugins. Null is refused before anything is written: clearing the list
     * would turn every plugin on for the customer.
     */
    @Transactional
    public void replace(int customerId, Integer[] pluginIds) {
        if (pluginIds == null) {
            throw new IllegalArgumentException("The list of disabled plugins is missing");
        }
        this.pluginMapper.cleanUpDisabledPlugins(customerId);
        insert(customerId, pluginIds);
    }

    /** Gives the customer the master account's list. */
    @Transactional
    public void copyFromMaster(int customerId) {
        List<DisabledPlugin> master = this.pluginMapper.getDisabledPluginsForCustomer(1);
        insert(customerId, master.stream().map(DisabledPlugin::getPluginId).toArray(Integer[]::new));
    }

    /** An INSERT with an empty VALUES list is invalid SQL, so no plugins means no statement. */
    private void insert(int customerId, Integer[] pluginIds) {
        if (pluginIds.length > 0) {
            this.pluginMapper.insertDisabledPlugin(pluginIds, customerId);
        }
    }
}
