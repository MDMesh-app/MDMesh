package com.hmdm.plugin;

import com.hmdm.plugin.persistence.PluginDAO;
import com.hmdm.plugin.persistence.domain.Plugin;
import com.hmdm.plugin.persistence.mapper.PluginMapper;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import com.mdmesh.fixture.FixturePluginConfiguration;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The console's server plugin list (GET /rest/plugin/main/private/available, `requires: { serverPlugin }`) holds what
 * the server's gate (PluginStatusCache) lets run for the user's customer: in the build, with a plugins row, not turned
 * off for everyone, and not turned off for the customer.
 */
public class PluginAvailabilityTest {

    @BeforeClass
    public static void scan() {
        PluginList.init(PluginDiscoveryTest.context());
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static Plugin plugin(int id, String identifier) {
        Plugin p = new Plugin();
        p.setId(id);
        p.setIdentifier(identifier);
        return p;
    }

    @Test
    public void theQueryLeavesOutPluginsTurnedOffForEveryoneOrForTheCustomer() {
        String where = Fakes.whereOf(Fakes.sqlOf(PluginMapper.class, "findAvailablePluginsByCustomerId"));
        assertTrue(where, where.contains("disabled = false"));
        assertTrue(where, where.contains("not exists (select 1 from pluginsdisabled"));
        assertTrue(where, where.contains("pluginsdisabled.customerid=#{customerid}"));
        assertTrue(where, where.contains("pluginsdisabled.pluginid=plugins.id"));
    }

    @Test
    public void theListIsTheSignedInCustomersAndOnlyPluginsInTheBuild() {
        FakeMapper<PluginMapper> mapper = new FakeMapper<>(PluginMapper.class)
                // "elsewhere" has a row but isn't in this build.
                .answer("findAvailablePluginsByCustomerId",
                        a -> Fakes.list(plugin(7, FixturePluginConfiguration.ID), plugin(8, "elsewhere")));
        SecurityContext.init(Fakes.user(5, 4, "configurations"));
        List<Plugin> available = new PluginDAO(mapper.mapper, null, null).findAvailablePlugins();
        assertEquals(Collections.singletonList(FixturePluginConfiguration.ID),
                available.stream().map(Plugin::getIdentifier).collect(Collectors.toList()));
        assertEquals(4, mapper.callsTo("findAvailablePluginsByCustomerId").get(0).args[0]);
    }
}
