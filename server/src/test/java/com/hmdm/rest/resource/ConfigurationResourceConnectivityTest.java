package com.hmdm.rest.resource;

import com.hmdm.persistence.domain.Configuration;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.BadRequestException;
import java.util.ArrayList;

/** PUT /configurations with a connectivityMode other than adaptive, alwaysOn or null is a 400, before anything is saved. */
public class ConfigurationResourceConnectivityTest {

    @Before
    public void setUp() {
        SecurityContext.init(Fakes.user(5, 1, "configurations"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static Configuration withMode(String mode) {
        Configuration c = new Configuration();
        c.setId(3);
        c.setName("warehouse");
        c.setApplications(new ArrayList<>());
        c.setConnectivityMode(mode);
        return c;
    }

    @Test(expected = BadRequestException.class)
    public void anUnknownModeIsABadRequest() {
        // Case matters: "AlwaysOn" would have been stored and sent as not managed.
        new ConfigurationResource().updateConfiguration(withMode("AlwaysOn"));
    }

    @Test(expected = BadRequestException.class)
    public void aModeLongerThanTheColumnIsABadRequest() {
        new ConfigurationResource().updateConfiguration(withMode("alwaysOnPleaseKeepItUp"));
    }

    @Test(expected = BadRequestException.class)
    public void anEmptyModeIsABadRequest() {
        new ConfigurationResource().updateConfiguration(withMode(""));
    }
}
