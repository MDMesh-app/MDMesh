/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A device's capability tree is stored and re-parsed on every check-in, so only bounded trees are accepted. */
public class AgentCapabilityLimitsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** The shape the current agent sends (agent-android proto CapabilityMatrix.Capabilities), generously filled. */
    private static JsonNode realAgentTree() throws Exception {
        return MAPPER.readTree("{"
                + "\"policy\":[\"wifi\",\"camera\",\"kioskLockTask\",\"screenCapture\",\"usb\",\"bluetooth\",\"location\","
                + "\"statusBar\",\"keyguard\",\"factoryReset\",\"safeBoot\",\"addUser\",\"mountMedia\",\"vpn\",\"tethering\","
                + "\"systemUpdate\",\"passwordQuality\",\"screenTimeout\",\"volume\",\"brightness\",\"autoTime\"],"
                + "\"appManagement\":[\"silentInstall\",\"splitApk\",\"silentUninstall\",\"hideApp\",\"permissionGrant\"],"
                + "\"device\":[\"lock\",\"wipe\",\"reboot\",\"ring\",\"ringStop\",\"alert\",\"passcodeReset\",\"lockscreenMessage\","
                + "\"locationMode\",\"powerMode\",\"configApply\"],"
                + "\"remoteControl\":{\"tier\":\"view\",\"screenCapture\":true,\"inputInjection\":false,\"transport\":[\"webrtc\"]},"
                + "\"oem\":{\"vendor\":\"samsung\",\"knox\":true}}");
    }

    @Test
    public void acceptsWhatTheAgentSends() throws Exception {
        JsonNode tree = realAgentTree();
        assertEquals(tree.toString(), AgentCapabilityTokens.boundedJson(tree));
        assertNull(AgentCapabilityTokens.boundedJson(null));
    }

    @Test
    public void rejectsDeepNesting() {
        ObjectNode root = F.objectNode();
        ObjectNode cur = root;
        for (int i = 0; i < 50; i++) {
            cur = cur.putObject("n");
        }
        assertNull(AgentCapabilityTokens.boundedJson(root));
    }

    @Test
    public void acceptsNestingUpToTheLimit() {
        ObjectNode root = F.objectNode();
        ObjectNode cur = root;
        for (int i = 1; i < AgentCapabilityTokens.MAX_TREE_DEPTH; i++) {
            cur = cur.putObject("n");
        }
        assertNotNull(AgentCapabilityTokens.boundedJson(root));
        cur.putObject("n");
        assertNull(AgentCapabilityTokens.boundedJson(root));
    }

    @Test
    public void rejectsHugeArrays() {
        ObjectNode root = F.objectNode();
        ArrayNode policy = root.putArray("policy");
        for (int i = 0; i < AgentCapabilityTokens.MAX_TREE_NODES + 1; i++) {
            policy.add("p");
        }
        assertNull(AgentCapabilityTokens.boundedJson(root));
    }

    @Test
    public void rejectsOversizedText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AgentCapabilityTokens.MAX_TREE_CHARS; i++) {
            sb.append('x');
        }
        ObjectNode root = F.objectNode();
        root.putArray("policy").add(sb.toString());
        assertNull(AgentCapabilityTokens.boundedJson(root));
    }

    @Test
    public void flattenIgnoresAnOversizedStoredMatrix() {
        StringBuilder sb = new StringBuilder("{\"policy\":[\"wifi\"],\"pad\":\"");
        while (sb.length() <= AgentCapabilityTokens.MAX_TREE_CHARS) {
            sb.append("xxxxxxxxxxxxxxxx");
        }
        sb.append("\"}");
        assertTrue(AgentCapabilityTokens.flatten(sb.toString()).isEmpty());
        assertTrue(AgentCapabilityTokens.flatten("{\"policy\":[\"wifi\"]}").contains("policy.wifi"));
    }
}
