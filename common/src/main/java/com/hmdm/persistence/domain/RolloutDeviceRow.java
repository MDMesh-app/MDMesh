/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.persistence.domain;

import java.io.Serializable;

/** A device's rollout-relevant facts: its number, last-reported agent version, and capability JSON. */
public class RolloutDeviceRow implements Serializable {
    private static final long serialVersionUID = 1L;

    private String deviceNumber;
    private String agentVersion;
    private String capabilitiesJson;

    public String getDeviceNumber() { return deviceNumber; }
    public void setDeviceNumber(String deviceNumber) { this.deviceNumber = deviceNumber; }

    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }

    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String capabilitiesJson) { this.capabilitiesJson = capabilitiesJson; }
}
