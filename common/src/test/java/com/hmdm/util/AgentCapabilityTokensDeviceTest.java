package com.hmdm.util;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentCapabilityTokensDeviceTest {

    @Test
    public void flattensDeviceArrayToDeviceTokens() {
        Set<String> tokens = AgentCapabilityTokens.flatten("{\"device\":[\"wipe\",\"ring\"]}");
        assertTrue(tokens.contains("device.wipe"));
        assertTrue(tokens.contains("device.ring"));
    }

    @Test
    public void deviceTokenGatesDelivery() {
        Set<String> tokens = AgentCapabilityTokens.flatten("{\"device\":[\"lock\"]}");
        assertTrue(AgentCapabilityTokens.isAllowed("device.lock", tokens));
        assertFalse(AgentCapabilityTokens.isAllowed("device.wipe", tokens));
    }
}
