package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Wire DTO for the compact device-state snapshot the agent piggybacks on each check-in. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentDeviceState {
    private Integer battery;
    private Boolean charging;
    private Boolean locked;
    private Boolean kioskActive;
    private String androidRelease;
    private Long lastBootAt;
    private String agentVersion;
    private String powerMode;

    public Integer getBattery() { return battery; }
    public void setBattery(Integer v) { this.battery = v; }
    public Boolean getCharging() { return charging; }
    public void setCharging(Boolean v) { this.charging = v; }
    public Boolean getLocked() { return locked; }
    public void setLocked(Boolean v) { this.locked = v; }
    public Boolean getKioskActive() { return kioskActive; }
    public void setKioskActive(Boolean v) { this.kioskActive = v; }
    public String getAndroidRelease() { return androidRelease; }
    public void setAndroidRelease(String v) { this.androidRelease = v; }
    public Long getLastBootAt() { return lastBootAt; }
    public void setLastBootAt(Long v) { this.lastBootAt = v; }
    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String v) { this.agentVersion = v; }
    public String getPowerMode() { return powerMode; }
    public void setPowerMode(String v) { this.powerMode = v; }
}
