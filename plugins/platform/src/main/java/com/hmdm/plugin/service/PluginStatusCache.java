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

package com.hmdm.plugin.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.User;
import com.hmdm.plugin.PluginList;
import com.hmdm.plugin.persistence.domain.DisabledPlugin;
import com.hmdm.plugin.persistence.domain.Plugin;
import com.hmdm.plugin.persistence.mapper.PluginMapper;
import com.hmdm.security.SecurityContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * <p>$</p>
 *
 * @author isv
 */
@Singleton
public class PluginStatusCache {

    /**
     * <p>An ORM mapper for domain object type.</p>
     */
    private final PluginMapper pluginMapper;

    private final Map<String, Plugin> pluginsByIdentifiers;
    private final Map<Integer, Set<Integer>> customerDisabledPlugins;

    /**
     * <p>Counts the writes reported ({@link #invalidate}). A read that a write
     * overtook is not kept; it may hold the rows from before the write. Guards the check-and-keep too.</p>
     */
    private final AtomicLong writes = new AtomicLong();

    /**
     * <p>Constructs new <code>PluginStatusCache</code> instance. This implementation does nothing.</p>
     */
    @Inject
    public PluginStatusCache(PluginMapper pluginMapper) {
        this.pluginMapper = pluginMapper;

        // In this build and not turned off for everyone (plugins.disabled, read here once: a change needs a restart),
        // as the console's list of a customer's plugins (PluginDAO.findAvailablePlugins) has them.
        final List<Plugin> registeredPlugins = this.pluginMapper.findRegisteredPlugins()
                .stream()
                .filter(p -> PluginList.isPluginEnabled(p.getIdentifier()))
                .filter(p -> !p.isDisabled())
                .collect(Collectors.toList());

        this.pluginsByIdentifiers = registeredPlugins.stream().collect(Collectors.toMap(Plugin::getIdentifier, p -> p));

        List<DisabledPlugin> disabledPluginsForCustomers = this.pluginMapper.getDisabledPluginsForAllCustomers();
        final Map<Integer, Set<Integer>> customerDisabledPlugins = new ConcurrentSkipListMap<>();
        disabledPluginsForCustomers.forEach(disabledPlugin -> {
            final int customerId = disabledPlugin.getCustomerId();
            if (!customerDisabledPlugins.containsKey(customerId)) {
                customerDisabledPlugins.put(customerId, new ConcurrentSkipListSet<>());
            }
            customerDisabledPlugins.get(customerId).add(disabledPlugin.getPluginId());
        });

        this.customerDisabledPlugins = customerDisabledPlugins;

    }

    /**
     * <p>Whether the plugin is off for the current user's customer (see {@link #isPluginDisabled(String, Integer)}).
     * Without a customer in the security context, only whether it is installed.</p>
     */
    public boolean isPluginDisabled(String pluginIdentifier) {
        final SecurityContext context = SecurityContext.get();
        final Integer customerId = context == null ? null : context.getCurrentCustomerId().orElse(null);
        // TODO : Need to check the potential anonymous calls from devices to plugin endpoints by getting the device number from request and mapping it to customer
        return isPluginDisabled(pluginIdentifier, customerId);
    }

    /**
     * <p>Whether the plugin is off for the customer: not in this build, without a <code>plugins</code> row, turned off
     * for everyone (its row's <code>disabled</code>, as of the server's start), or turned off for that customer. For
     * callers without a signed-in user, such as a device's check-in. A customer's state is read once and then kept
     * (and dropped by {@link #invalidate} after a write), so asking costs no query.</p>
     *
     * @param customerId the customer; null to check only that the plugin is installed (and not off for everyone).
     */
    public boolean isPluginDisabled(String pluginIdentifier, Integer customerId) {
        final Plugin plugin = this.pluginsByIdentifiers.get(pluginIdentifier);
        if (plugin == null) {
            return true;
        }
        if (customerId == null) {
            return false;
        }
        Set<Integer> disabled = this.customerDisabledPlugins.get(customerId);
        if (disabled == null) {
            disabled = load(customerId);
        }
        return disabled.contains(plugin.getId());
    }

    /**
     * <p>Reads the customer's rows and keeps them, unless a write was reported while they were being read: that read
     * may be from before the write, so it answers this ask only and the next ask reads again.</p>
     */
    private Set<Integer> load(int customerId) {
        final long generation = this.writes.get();
        final Set<Integer> read = this.pluginMapper.getDisabledPluginsForCustomer(customerId)
                .stream()
                .map(DisabledPlugin::getPluginId)
                .collect(Collectors.toCollection(ConcurrentSkipListSet::new));
        synchronized (this.writes) {
            if (this.writes.get() == generation) {
                final Set<Integer> kept = this.customerDisabledPlugins.putIfAbsent(customerId, read);
                return kept != null ? kept : read;
            }
        }
        return read;
    }

    /**
     * <p>Drops the customer's state, so the next ask reads it from the database. Call it after every write of the
     * customer's <code>pluginsDisabled</code> rows has committed (PluginDAO does), or failed.</p>
     */
    public void invalidate(int customerId) {
        synchronized (this.writes) {
            this.writes.incrementAndGet();
            this.customerDisabledPlugins.remove(customerId);
        }
    }
}
