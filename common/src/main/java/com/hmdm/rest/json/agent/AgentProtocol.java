/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
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
