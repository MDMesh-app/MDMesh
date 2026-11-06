/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.notification.PushService;
import com.hmdm.persistence.ConfigurationDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.BadRequestException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Devices apply the configuration's system update setting (config.apply systemUpdate, protocol 1.6), so a save that
 * changes it is refused when the type is unknown or a Scheduled window isn't two different HH:MM times: such a window
 * would reach the device as Default. A row already stored that way can still get an unrelated edit.
 */
public class ConfigurationSystemUpdateTest {

    /** Stores into fields instead of a database. */
    private static final class FakeConfigurationDAO extends ConfigurationDAO {
        Configuration stored;
        boolean access = true;
        final List<Configuration> updated = new ArrayList<>();
        final List<Configuration> inserted = new ArrayList<>();

        FakeConfigurationDAO() {
            super(null, null, null, null, "https://mdm.example.com", System.getProperty("java.io.tmpdir"), null);
        }

        @Override public Configuration getConfigurationByName(String name) { return null; }
        @Override public boolean hasConfigurationAccess(int id) { return access; }
        @Override public void updateConfiguration(Configuration c) { updated.add(c); }
        @Override public void insertConfiguration(Configuration c) { c.setId(99); inserted.add(c); }
        @Override public Configuration getConfigurationByIdFull(Integer id) { return stored; }
        @Override public Configuration getConfigurationById(Integer id) { return stored; }
        @Override public String getBaseUrl() { return "https://mdm.example.com"; }
    }

    private FakeConfigurationDAO dao;
    private ConfigurationResource resource;

    @Before
    public void setUp() {
        SecurityContext.init(Fakes.user(5, 1, "configurations", "add_config"));
        dao = new FakeConfigurationDAO();
        resource = new ConfigurationResource(dao, null, new PushService(null, null, null, null, false), null, null,
                "https://mdm.example.com", Fakes.extensionPoints(Collections.emptySet(), Collections.emptySet()));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static Configuration cfg(int type, String from, String to) {
        Configuration c = new Configuration();
        c.setName("warehouse");
        c.setApplications(new ArrayList<>());
        c.setSystemUpdateType(type);
        c.setSystemUpdateFrom(from);
        c.setSystemUpdateTo(to);
        return c;
    }

    @Test
    public void theFourTypesAreAccepted() {
        assertNull(ConfigurationResource.systemUpdateError(cfg(0, null, null)));
        assertNull(ConfigurationResource.systemUpdateError(cfg(1, null, null)));
        assertNull(ConfigurationResource.systemUpdateError(cfg(2, "22:00", "04:00")));
        assertNull(ConfigurationResource.systemUpdateError(cfg(3, null, null)));
    }

    @Test
    public void timesOnlyMatterForScheduled() {
        // The Headwind-era console saved times with any type; they are kept and ignored.
        assertNull(ConfigurationResource.systemUpdateError(cfg(0, "nonsense", "")));
        assertNull(ConfigurationResource.systemUpdateError(cfg(1, "22:00", "22:00")));
    }

    @Test
    public void anUnknownTypeIsRefused() {
        assertNotNull(ConfigurationResource.systemUpdateError(cfg(4, null, null)));
        assertNotNull(ConfigurationResource.systemUpdateError(cfg(-1, null, null)));
    }

    @Test
    public void aScheduledTypeNeedsTwoDifferentValidTimes() {
        assertNotNull(ConfigurationResource.systemUpdateError(cfg(2, null, "04:00")));
        assertNotNull(ConfigurationResource.systemUpdateError(cfg(2, "22:00", "")));
        assertNotNull(ConfigurationResource.systemUpdateError(cfg(2, "22:00", "22:00")));
        assertNotNull(ConfigurationResource.systemUpdateError(cfg(2, "24:00", "04:00")));
        assertNotNull(ConfigurationResource.systemUpdateError(cfg(2, "22:00", "4 am")));
    }

    /** A PUT of {@code save} over the stored row {@code stored} (a new configuration when null). */
    private Response put(Configuration stored, Configuration save) {
        if (stored != null) {
            stored.setId(3);
            save.setId(3);
        }
        dao.stored = stored;
        return resource.updateConfiguration(save);
    }

    private void assertRefused(Configuration stored, Configuration save) {
        try {
            put(stored, save);
            fail("the save was not refused");
        } catch (BadRequestException expected) {
            assertTrue(dao.updated.isEmpty());
            assertTrue(dao.inserted.isEmpty());
        }
    }

    @Test
    public void aNewConfigurationIsChecked() {
        assertRefused(null, cfg(2, "", ""));
        assertRefused(null, cfg(2, "22:00", "22:00"));
        assertRefused(null, cfg(5, null, null));
    }

    @Test
    public void aSaveThatChangesTheSettingIsChecked() {
        assertRefused(cfg(0, null, null), cfg(2, null, null));
        assertRefused(cfg(0, null, null), cfg(5, null, null));
        assertRefused(cfg(2, "22:00", "04:00"), cfg(2, "22:00", "22:00"));
        assertRefused(cfg(2, "22:00", "04:00"), cfg(2, "9:00", "04:00"));
    }

    @Test
    public void aBadStoredRowIsCheckedOnceItsSettingChanges() {
        assertRefused(cfg(2, "", ""), cfg(2, "22:00", ""));
        assertRefused(cfg(2, "22:00", "22:00"), cfg(2, "22:00", "21:00:00"));
        assertRefused(cfg(5, null, null), cfg(4, null, null));
    }

    @Test
    public void anUnrelatedEditOfABadStoredRowIsSaved() {
        int saves = 0;
        for (Configuration[] row : new Configuration[][] {
            {cfg(2, "", ""), cfg(2, "", "")},
            {cfg(2, null, null), cfg(2, null, null)},
            {cfg(2, "22:00", "22:00"), cfg(2, "22:00", "22:00")},
            {cfg(2, "25:00", "04:00"), cfg(2, "25:00", "04:00")},
            {cfg(5, null, null), cfg(5, null, null)},
            // The console's time field clears to null, an API client may trim: neither is a change.
            {cfg(2, "", " "), cfg(2, null, "")},
            {cfg(2, "9:00", "04:00"), cfg(2, " 9:00", "04:00 ")},
        }) {
            row[1].setDescription("Back room");
            put(row[0], row[1]);
            assertEquals(++saves, dao.updated.size());
        }
    }

    @Test
    public void aGoodSettingIsSaved() {
        put(cfg(0, null, null), cfg(2, "22:00", "04:00"));
        assertEquals(1, dao.updated.size());
        put(null, cfg(2, "22:00", "04:00"));
        assertEquals(1, dao.inserted.size());
    }

    @Test
    public void aBadStoredRowCanBeMovedToAnotherType() {
        put(cfg(2, "", ""), cfg(0, "", ""));
        assertEquals(1, dao.updated.size());
    }

    @Test
    public void noAccessIsDeniedBeforeTheSettingIsChecked() {
        dao.access = false;
        Response r = put(cfg(0, null, null), cfg(5, null, null));
        assertEquals(Response.PERMISSION_DENIED().getMessage(), r.getMessage());
        assertTrue(dao.updated.isEmpty());
    }

    @Test
    public void blankAndNullTimesAreTheSame() {
        assertFalse(ConfigurationResource.systemUpdateChanges(cfg(2, "", null), cfg(2, null, " ")));
        assertTrue(ConfigurationResource.systemUpdateChanges(cfg(2, "22:00", null), cfg(2, null, null)));
        assertTrue(ConfigurationResource.systemUpdateChanges(cfg(2, null, "04:00"), cfg(2, null, null)));
        assertTrue(ConfigurationResource.systemUpdateChanges(cfg(1, null, null), cfg(2, null, null)));
        assertTrue(ConfigurationResource.systemUpdateChanges(cfg(1, null, null), null));
    }
}
