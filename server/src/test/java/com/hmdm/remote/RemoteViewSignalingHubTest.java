package com.hmdm.remote;

import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteViewSignalingHubTest {
    @Test public void acceptsOnlyViewOnlyWebRtcSignalingEnvelopesForTheirRole() {
        assertTrue(RemoteViewSignalingHub.validEnvelope("{\"type\":\"offer\",\"payload\":{\"sdp\":\"x\"}}", true));
        assertTrue(RemoteViewSignalingHub.validEnvelope("{\"type\":\"answer\",\"payload\":{\"sdp\":\"x\"}}", false));
        assertTrue(RemoteViewSignalingHub.validEnvelope("{\"type\":\"candidate\",\"payload\":{\"candidate\":\"x\"}}", true));
        assertFalse(RemoteViewSignalingHub.validEnvelope("{\"type\":\"answer\",\"payload\":{}}", true));
        assertFalse(RemoteViewSignalingHub.validEnvelope("{\"type\":\"control\",\"payload\":{}}", false));
        assertFalse(RemoteViewSignalingHub.validEnvelope("{\"type\":\"offer\",\"payload\":{},\"extra\":true}", true));
    }
}
