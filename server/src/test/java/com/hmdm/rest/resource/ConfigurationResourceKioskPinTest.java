package com.hmdm.rest.resource;

import com.hmdm.persistence.domain.Configuration;
import com.hmdm.util.KioskExitPin;
import org.junit.Test;

import javax.ws.rs.BadRequestException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * PUT /configurations: a new or changed kiosk exit PIN must be 6 to 12 digits or empty, and is refused with HTTP 400
 * and the rule as the message; a PIN stored before the rule, sent back unchanged, keeps the configuration saveable.
 */
public class ConfigurationResourceKioskPinTest {

    private static Configuration withPin(String pin) {
        Configuration c = new Configuration();
        c.setId(3);
        c.setName("warehouse");
        c.setPassword(pin);
        return c;
    }

    private static void refused(Configuration c, Configuration stored) {
        try {
            ConfigurationResource.validateKioskPin(c, stored);
            fail("PIN " + c.getPassword() + " was saved");
        } catch (BadRequestException e) {
            assertEquals(400, e.getResponse().getStatus());
            com.hmdm.rest.json.Response body = (com.hmdm.rest.json.Response) e.getResponse().getEntity();
            assertEquals(KioskExitPin.FORMAT_MESSAGE, body.getMessage());
        }
    }

    @Test
    public void aNewConfigurationWithAShortOrNonDigitPinIsRefused() {
        refused(withPin("1234"), null);
        refused(withPin("letmeout"), null);
        refused(withPin("1234567890123"), null);
    }

    @Test
    public void aValidPinOrNoneIsSaved() {
        ConfigurationResource.validateKioskPin(withPin("482139"), null);
        ConfigurationResource.validateKioskPin(withPin(""), null);
        ConfigurationResource.validateKioskPin(withPin(null), withPin("letmeout"));
    }

    @Test
    public void aLegacyPinSentBackUnchangedKeepsTheConfigurationSaveable() {
        ConfigurationResource.validateKioskPin(withPin("let me out"), withPin("let me out"));
    }

    @Test
    public void changingALegacyPinToAnotherLegacyOneIsRefused() {
        refused(withPin("let me out!"), withPin("let me out"));
        refused(withPin("4321"), withPin("1234"));
    }
}
