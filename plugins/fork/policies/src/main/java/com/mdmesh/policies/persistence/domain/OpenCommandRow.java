package com.mdmesh.policies.persistence.domain;

/** A queued or running app scan or uninstall of one phone ({@code FleetMapper.listOpenCommands}). */
public class OpenCommandRow {
    private String deviceNumber;
    private String type;
    private String payload;

    public String getDeviceNumber() { return deviceNumber; }
    public void setDeviceNumber(String deviceNumber) { this.deviceNumber = deviceNumber; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
