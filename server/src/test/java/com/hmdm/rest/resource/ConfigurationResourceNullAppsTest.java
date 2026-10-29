package com.hmdm.rest.resource;

import com.hmdm.persistence.domain.Configuration;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.BadRequestException;

/** PUT /configurations with "applications": null (or no body) is a 400, not a null-pointer 500. */
public class ConfigurationResourceNullAppsTest {

    @Before
    public void setUp() {
        SecurityContext.init(Fakes.user(5, 1, "configurations"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test(expected = BadRequestException.class)
    public void nullApplicationsIsABadRequest() {
        Configuration c = new Configuration();
        c.setId(3);
        c.setName("warehouse");
        c.setApplications(null);
        new ConfigurationResource().updateConfiguration(c);
    }

    @Test(expected = BadRequestException.class)
    public void aMissingBodyIsABadRequest() {
        new ConfigurationResource().updateConfiguration(null);
    }
}
