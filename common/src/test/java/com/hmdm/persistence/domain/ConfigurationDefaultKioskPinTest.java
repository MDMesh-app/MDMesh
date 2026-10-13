package com.hmdm.persistence.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The configuration API flags a kiosk exit PIN that is still the well-known default of older installs, so the console
 * can warn about it. The flag is derived and read-only: the console sends the configuration back on save, and that
 * must keep working, and the PIN itself stays readable to whoever can read the configuration.
 */
public class ConfigurationDefaultKioskPinTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void theFlagFollowsTheStoredPin() {
        Configuration c = new Configuration();
        assertFalse(c.isDefaultKioskPin());
        c.setPassword("12345678");
        assertTrue(c.isDefaultKioskPin());
        c.setPassword(" 12345678 ");
        assertFalse(c.isDefaultKioskPin());
        c.setPassword("40917253");
        assertFalse(c.isDefaultKioskPin());
        c.setPassword("");
        assertFalse(c.isDefaultKioskPin());
    }

    @Test
    public void theApiCarriesTheFlagAndThePin() throws Exception {
        Configuration c = new Configuration();
        c.setPassword("12345678");
        JsonNode json = JSON.valueToTree(c);
        assertTrue(json.get("defaultKioskPin").asBoolean());
        assertEquals("12345678", json.get("password").asText());
    }

    @Test
    public void aSavedConfigurationThatCarriesTheFlagStillReads() throws Exception {
        Configuration c = JSON.readValue("{\"name\":\"Kiosk\",\"password\":\"40917253\",\"defaultKioskPin\":true}",
                Configuration.class);
        assertEquals("40917253", c.getPassword());
        assertFalse(c.isDefaultKioskPin());
    }
}
