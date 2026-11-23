package com.hmdm.plugin;

import com.hmdm.plugin.persistence.domain.DisabledPlugin;
import com.hmdm.plugin.persistence.domain.Plugin;
import com.hmdm.plugin.persistence.mapper.PluginMapper;
import com.hmdm.plugin.service.PluginStatusCache;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import com.mdmesh.fixture.FixturePluginConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Whether a server plugin is on for a customer, as PluginAccessFilter and the extension points ask it: by customer id,
 * for a check-in, which has no signed-in user. Each customer's state is read once, so asking costs no query.
 */
public class PluginStatusCacheTest {

    private static final String FIXTURE = FixturePluginConfiguration.ID;
    private static final int FIXTURE_ROW = 7;

    private FakeMapper<PluginMapper> mapper;
    private PluginStatusCache cache;

    private static Plugin plugin(int id, String identifier) {
        Plugin p = new Plugin();
        p.setId(id);
        p.setIdentifier(identifier);
        return p;
    }

    private static DisabledPlugin disabled(int customerId, int pluginId) {
        DisabledPlugin d = new DisabledPlugin();
        d.setCustomerId(customerId);
        d.setPluginId(pluginId);
        return d;
    }

    @BeforeClass
    public static void scan() {
        // The fixture plugin is in this build, as a plugin in launcher.war is.
        PluginList.init(PluginDiscoveryTest.context());
    }

    @Before
    public void setUp() {
        mapper = new FakeMapper<>(PluginMapper.class)
                // "elsewhere" has a row but isn't in this build.
                .answer("findRegisteredPlugins", a -> Fakes.list(plugin(FIXTURE_ROW, FIXTURE), plugin(8, "elsewhere")))
                .answer("getDisabledPluginsForAllCustomers", a -> Fakes.list(disabled(2, FIXTURE_ROW)))
                .answer("getDisabledPluginsForCustomer", a -> Collections.emptyList());
        cache = new PluginStatusCache(mapper.mapper);
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void aPluginIsOffOnlyForTheCustomersThatTurnedItOff() {
        assertFalse(cache.isPluginDisabled(FIXTURE, 1));
        assertTrue(cache.isPluginDisabled(FIXTURE, 2));
    }

    @Test
    public void aPluginWithoutARowOrNotInTheBuildIsOffForEveryone() {
        assertTrue(cache.isPluginDisabled("unknown", 1));
        assertTrue(cache.isPluginDisabled("elsewhere", 1));
    }

    /**
     * A plugin turned off for everyone (plugins.disabled, read at start) is off for every customer, as the console's
     * plugin list (/plugin/main/private/available) leaves it out.
     */
    @Test
    public void aPluginDisabledGloballyIsOffForEveryone() {
        Plugin off = plugin(FIXTURE_ROW, FIXTURE);
        off.setDisabled(true);
        FakeMapper<PluginMapper> disabled = new FakeMapper<>(PluginMapper.class)
                .answer("findRegisteredPlugins", a -> Fakes.list(off))
                .answer("getDisabledPluginsForCustomer", a -> Collections.emptyList());
        PluginStatusCache globallyOff = new PluginStatusCache(disabled.mapper);
        assertTrue(globallyOff.isPluginDisabled(FIXTURE, 1));
        assertTrue(globallyOff.isPluginDisabled(FIXTURE, null));
    }

    @Test
    public void aCustomersStateIsReadOnceThenKept() {
        for (int i = 0; i < 100; i++) {
            assertFalse(cache.isPluginDisabled(FIXTURE, 3));
            assertTrue(cache.isPluginDisabled(FIXTURE, 2));
        }
        // Customer 3 has no row, so it is read once; customer 2's was read at start.
        assertEquals(1, mapper.callsTo("getDisabledPluginsForCustomer").size());
    }

    /** PluginDAO drops the entry after each write, so the next ask reads the customer's rows as they now are. */
    @Test
    public void turningItOffForACustomerCountsOnceTheEntryIsDropped() {
        assertFalse(cache.isPluginDisabled(FIXTURE, 1));
        mapper.answer("getDisabledPluginsForCustomer", a -> Fakes.list(disabled(1, FIXTURE_ROW)));
        assertFalse("kept until dropped", cache.isPluginDisabled(FIXTURE, 1));
        cache.invalidate(1);
        assertTrue(cache.isPluginDisabled(FIXTURE, 1));
    }

    @Test
    public void withoutACustomerItAsksForTheSignedInUsers() {
        SecurityContext.init(Fakes.user(5, 2, "configurations"));
        assertTrue(cache.isPluginDisabled(FIXTURE));
        SecurityContext.release();
        SecurityContext.init(Fakes.user(5, 1, "configurations"));
        assertFalse(cache.isPluginDisabled(FIXTURE));
    }
}
