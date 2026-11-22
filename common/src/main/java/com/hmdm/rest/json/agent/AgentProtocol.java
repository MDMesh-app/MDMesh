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

package com.hmdm.rest.json.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>Constants for the from-scratch Android agent v1 wire protocol.</p>
 */
public final class AgentProtocol {

    /** The protocol version advertised and required by the v1 endpoints. */
    public static final String VERSION = "1.12";

    private static final Pattern MAJOR_MINOR = Pattern.compile("^(\\d{1,9})\\.(\\d{1,9})$");

    private AgentProtocol() {
    }

    /**
     * Whether a peer's {@code protocolVersion} is {@code major.minor} or later, compared as numbers ("1.12" is after
     * "1.9"). False for a missing or malformed version: the peer is treated as the oldest one.
     */
    public static boolean atLeast(String version, int major, int minor) {
        Matcher m = version == null ? null : MAJOR_MINOR.matcher(version);
        if (m == null || !m.matches()) {
            return false;
        }
        int peerMajor = Integer.parseInt(m.group(1));
        return peerMajor != major ? peerMajor > major : Integer.parseInt(m.group(2)) >= minor;
    }
}
