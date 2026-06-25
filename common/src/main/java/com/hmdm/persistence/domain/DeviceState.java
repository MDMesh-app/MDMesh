package com.hmdm.persistence.domain;

import java.io.Serializable;

/** Latest agent-reported device snapshot (one row per device number). */
public class DeviceState implements Serializable {
    private static final long serialVersionUID = 1L;
    private String deviceNumber;
    private Integer battery;
    private Boolean charging;
    private Boolean locked;
    private Boolean kioskActive;
    private String androidRelease;
    private Long lastBootAt;
    private Long updatedAt;
    private String agentVersion;
    private String powerMode;
    private String telemetry;

    public String getDeviceNumber() { return deviceNumber; }
    public void setDeviceNumber(String v) { this.deviceNumber = v; }
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
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long v) { this.updatedAt = v; }
    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String v) { this.agentVersion = v; }
    public String getPowerMode() { return powerMode; }
    public void setPowerMode(String v) { this.powerMode = v; }
    public String getTelemetry() { return telemetry; }
    public void setTelemetry(String v) { this.telemetry = v; }
}
