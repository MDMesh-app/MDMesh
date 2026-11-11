package com.hmdm.persistence.domain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConfigurationDnsTest {

    @Test
    public void testConfigurationDnsPropertyAndCopy() {
        Configuration config = new Configuration();
        config.setName("Test Config");
        config.setDns("1.1.1.1");

        assertEquals("1.1.1.1", config.getDns());

        Configuration copy = config.newCopy();
        assertEquals("1.1.1.1", copy.getDns());
    }
}
