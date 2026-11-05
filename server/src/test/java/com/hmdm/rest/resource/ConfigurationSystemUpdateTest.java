/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.domain.Configuration;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Devices apply the configuration's system update setting (config.apply systemUpdate, protocol 1.6), so a save is
 * refused when the type is unknown or a Scheduled window isn't two different HH:MM times: such a window would reach
 * the device as Default.
 */
public class ConfigurationSystemUpdateTest {

    private static Configuration cfg(int type, String from, String to) {
        Configuration c = new Configuration();
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
}
