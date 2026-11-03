/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.plugin.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.plugin.PluginList;
import com.hmdm.plugin.persistence.domain.Plugin;
import com.hmdm.plugin.persistence.mapper.PluginMapper;
import com.hmdm.plugin.service.PluginStatusCache;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>A DAO for {@link Plugin} domain objects.</p>
 *
 * @author isv
 */
@Singleton
public class PluginDAO {

    /**
     * <p>An ORM mapper for domain object type.</p>
     */
    private final PluginMapper pluginMapper;

    /**
     * <p>The per-customer plugin state. Every write of <code>pluginsDisabled</code> goes through this DAO, which drops
     * the customer's entry after the write's transaction ends (committed or not), so the next ask reads the table.</p>
     */
    private final PluginStatusCache pluginStatusCache;

    private final DisabledPluginRows disabledPluginRows;

    /**
     * <p>Constructs new <code>PluginDAO</code> instance. This implementation does nothing.</p>
     */
    @Inject
    public PluginDAO(PluginMapper pluginMapper, PluginStatusCache pluginStatusCache, DisabledPluginRows disabledPluginRows) {
        this.pluginMapper = pluginMapper;
        this.pluginStatusCache = pluginStatusCache;
        this.disabledPluginRows = disabledPluginRows;
    }

    /**
     * <p>Gets the list of plugins available for customer account associated with the current user.</p>
     *
     * @return a list of available plugins.
     */
    public List<Plugin> findAvailablePlugins() {
        return SecurityContext.get()
                .getCurrentUser()
                .map(u -> this.pluginMapper.findAvailablePluginsByCustomerId(u.getCustomerId())
                        .stream()
                        .filter(p -> PluginList.isPluginEnabled(p.getIdentifier()))
                        .collect(Collectors.toList())
                )
                .orElse(new ArrayList<>());
    }

    /**
     * <p>Gets the list of registered plugins, e.g. those plugins which are installed in the application.</p>
     *
     * @return a list of registered plugins.
     */
    public List<Plugin> findRegisteredPlugins() {
        return this.pluginMapper.findRegisteredPlugins()
                .stream()
                .filter(p -> PluginList.isPluginEnabled(p.getIdentifier()))
                .collect(Collectors.toList());
    }

    /**
     * <p>Gets the list of active plugins, e.g. those plugins which are installed in the application and are not marked
     * as disabled.</p>
     *
     * @return a list of active plugins.
     */
    public List<Plugin> findActivePlugins() {
        return this.pluginMapper.findActivePlugins()
                .stream()
                .filter(p -> PluginList.isPluginEnabled(p.getIdentifier()))
                .collect(Collectors.toList());
    }

    /**
     * <p>Disables the specified plugins for customer account associated with the current user.</p>
     *
     * @param pluginIds a list of plugin IDs.
     */
    public void saveDisabledPlugins(Integer[] pluginIds) {
        final int customerId = SecurityContext.get().getCurrentUser()
                .orElseThrow(SecurityException::onAnonymousAccess)
                .getCustomerId();
        try {
            this.disabledPluginRows.replace(customerId, pluginIds);
        } finally {
            this.pluginStatusCache.invalidate(customerId);
        }
    }

    /**
     * <p>Copies the disabled plugins from the master (super-admin) account to the customer account.</p>
     */
    public void copyDisabledPluginsFromMaster(int customerId) {
        try {
            this.disabledPluginRows.copyFromMaster(customerId);
        } finally {
            // Something may have asked about the new customer before this copy (it runs in a background task).
            this.pluginStatusCache.invalidate(customerId);
        }
    }
}
