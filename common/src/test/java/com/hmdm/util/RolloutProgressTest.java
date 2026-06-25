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

import com.hmdm.persistence.domain.RolloutDeviceRow;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/** Unit tests for the pure rollout-progress classifier (no DB). */
public class RolloutProgressTest {

    /** appManagement:["silentInstall"] flattens to the gate token app.silentInstall. */
    private static final String CAPS_OK = "{\"appManagement\":[\"silentInstall\"]}";
    /** No app-management capability ⇒ ineligible (the delivery gate would block the install). */
    private static final String CAPS_NONE = "{\"policy\":[\"wifi\"]}";

    private static RolloutDeviceRow row(String num, String version, String caps) {
        RolloutDeviceRow r = new RolloutDeviceRow();
        r.setDeviceNumber(num);
        r.setAgentVersion(version);
        r.setCapabilitiesJson(caps);
        return r;
    }

    @Test
    public void classifiesEachState() {
        assertEquals(RolloutProgress.Status.UPDATED,
                RolloutProgress.classify("1.2.0", row("a", "1.2.0", CAPS_OK), false));
        assertEquals(RolloutProgress.Status.INELIGIBLE,
                RolloutProgress.classify("1.2.0", row("d", "1.1.0", CAPS_NONE), false));
        assertEquals(RolloutProgress.Status.PENDING,
                RolloutProgress.classify("1.2.0", row("b", "1.1.0", CAPS_OK), true));
        assertEquals(RolloutProgress.Status.OUTSTANDING,
                RolloutProgress.classify("1.2.0", row("c", "1.1.0", CAPS_OK), false));
    }

    @Test
    public void updatedBeatsIneligible() {
        // A device already on target is UPDATED even if it no longer advertises the capability.
        assertEquals(RolloutProgress.Status.UPDATED,
                RolloutProgress.classify("1.2.0", row("a", "1.2.0", CAPS_NONE), false));
    }

    @Test
    public void countsAddUp() {
        List<RolloutDeviceRow> rows = Arrays.asList(
                row("a", "1.2.0", CAPS_OK),   // updated
                row("b", "1.1.0", CAPS_OK),   // pending
                row("c", "1.1.0", CAPS_OK),   // outstanding
                row("d", "1.1.0", CAPS_NONE)  // ineligible
        );
        Set<String> pending = new HashSet<>(Collections.singletonList("b"));
        RolloutProgress.Counts c = RolloutProgress.counts("1.2.0", rows, pending);
        assertEquals(4, c.getTotal());
        assertEquals(1, c.getUpdated());
        assertEquals(1, c.getPending());
        assertEquals(1, c.getOutstanding());
        assertEquals(1, c.getIneligible());
    }

    @Test
    public void emptyCohortIsZero() {
        RolloutProgress.Counts c = RolloutProgress.counts("1.2.0", Collections.emptyList(), null);
        assertEquals(0, c.getTotal());
    }
}
