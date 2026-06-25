/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.Set;

/**
 * <p>A test suite for {@link AgentCapabilityTokens}: capability-matrix flattening and the gating
 * predicate. Pure-unit, no Spring/Guice/DB.</p>
 */
public class AgentCapabilityTokensTest {

    public AgentCapabilityTokensTest() {
    }

    // ---- flatten() --------------------------------------------------------------------------------------------------

    @Test
    public void testFlattenPolicyAndAppTokens() {
        String json = "{\"policy\":[\"wifi\",\"camera\"],\"appManagement\":[\"silentInstall\"]}";
        Set<String> tokens = AgentCapabilityTokens.flatten(json);
        Assert.assertEquals(3, tokens.size());
        Assert.assertTrue(tokens.contains("policy.wifi"));
        Assert.assertTrue(tokens.contains("policy.camera"));
        Assert.assertTrue(tokens.contains("app.silentInstall"));
    }

    @Test
    public void testFlattenRemoteTierEmittedWhenNotNone() {
        Set<String> view = AgentCapabilityTokens.flatten("{\"remoteControl\":{\"tier\":\"view\"}}");
        Assert.assertTrue(view.contains("remote.view"));

        Set<String> control = AgentCapabilityTokens.flatten("{\"remoteControl\":{\"tier\":\"control\"}}");
        Assert.assertTrue(control.contains("remote.control"));
    }

    @Test
    public void testFlattenRemoteTierNoneIsSkipped() {
        Set<String> tokens = AgentCapabilityTokens.flatten("{\"remoteControl\":{\"tier\":\"none\"}}");
        Assert.assertTrue("tier=none must not emit a remote.* token", tokens.isEmpty());
    }

    @Test
    public void testFlattenOemKnoxOnlyWhenTrue() {
        Set<String> knoxOn = AgentCapabilityTokens.flatten("{\"oem\":{\"vendor\":\"samsung\",\"knox\":true}}");
        Assert.assertTrue(knoxOn.contains("oem.knox"));

        Set<String> knoxOff = AgentCapabilityTokens.flatten("{\"oem\":{\"vendor\":\"samsung\",\"knox\":false}}");
        Assert.assertFalse(knoxOff.contains("oem.knox"));
    }

    @Test
    public void testFlattenFullMatrix() {
        String json = "{"
                + "\"policy\":[\"wifi\",\"kioskLockTask\"],"
                + "\"appManagement\":[\"silentInstall\"],"
                + "\"remoteControl\":{\"tier\":\"control\"},"
                + "\"oem\":{\"vendor\":\"samsung\",\"knox\":true}"
                + "}";
        Set<String> tokens = AgentCapabilityTokens.flatten(json);
        Assert.assertTrue(tokens.contains("policy.wifi"));
        Assert.assertTrue(tokens.contains("policy.kioskLockTask"));
        Assert.assertTrue(tokens.contains("app.silentInstall"));
        Assert.assertTrue(tokens.contains("remote.control"));
        Assert.assertTrue(tokens.contains("oem.knox"));
        Assert.assertEquals(5, tokens.size());
    }

    @Test
    public void testFlattenNullAndBlankAndMalformedAreEmpty() {
        Assert.assertTrue(AgentCapabilityTokens.flatten((String) null).isEmpty());
        Assert.assertTrue(AgentCapabilityTokens.flatten("").isEmpty());
        Assert.assertTrue(AgentCapabilityTokens.flatten("   ").isEmpty());
        Assert.assertTrue(AgentCapabilityTokens.flatten("not json").isEmpty());
        Assert.assertTrue(AgentCapabilityTokens.flatten("[1,2,3]").isEmpty());
    }

    @Test
    public void testFlattenUnknownKeysIgnored() {
        // A newer agent advertising a key an older server never heard of must not break flattening.
        Set<String> tokens = AgentCapabilityTokens.flatten("{\"policy\":[\"wifi\"],\"futureThing\":{\"x\":1}}");
        Assert.assertEquals(Collections.singleton("policy.wifi"), tokens);
    }

    // ---- isAllowed() (the gate) -------------------------------------------------------------------------------------

    @Test
    public void testNullRequiresCapabilityAlwaysPasses() {
        Assert.assertTrue(AgentCapabilityTokens.isAllowed(null, Collections.emptySet()));
        Assert.assertTrue(AgentCapabilityTokens.isAllowed("", Collections.emptySet()));
        Assert.assertTrue(AgentCapabilityTokens.isAllowed(null, null));
    }

    @Test
    public void testRequiredTokenPresentPasses() {
        Set<String> deviceTokens = AgentCapabilityTokens.flatten("{\"policy\":[\"wifi\"]}");
        Assert.assertTrue(AgentCapabilityTokens.isAllowed("policy.wifi", deviceTokens));
    }

    @Test
    public void testRequiredTokenAbsentBlocked() {
        Set<String> deviceTokens = AgentCapabilityTokens.flatten("{\"policy\":[\"wifi\"]}");
        Assert.assertFalse(AgentCapabilityTokens.isAllowed("app.silentInstall", deviceTokens));
        Assert.assertFalse(AgentCapabilityTokens.isAllowed("policy.camera", deviceTokens));
    }

    @Test
    public void testRequiredTokenAgainstNullOrEmptySetBlocked() {
        Assert.assertFalse(AgentCapabilityTokens.isAllowed("policy.wifi", null));
        Assert.assertFalse(AgentCapabilityTokens.isAllowed("policy.wifi", Collections.emptySet()));
    }
}
