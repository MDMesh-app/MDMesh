package com.mdmesh.policies.persistence.domain;

/** A phone on a schedule's policy, with what decides whether it is targeted and whether it can open apps. */
public class SchedulePhone {
    private int id;
    private String number;
    private String description;
    /** The capabilities its agent last reported (JSON). */
    private String capabilities;
    /** Its latest telemetry (JSON text); its hardware brand and manufacturer are read from it, leniently. */
    private String telemetry;
    /** The brand set on it by hand (Set brand). */
    private String setBrand;
    /** The group it was picked into by hand. */
    private Integer pickedGroupId;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCapabilities() { return capabilities; }
    public void setCapabilities(String capabilities) { this.capabilities = capabilities; }
    public String getTelemetry() { return telemetry; }
    public void setTelemetry(String telemetry) { this.telemetry = telemetry; }
    public String getSetBrand() { return setBrand; }
    public void setSetBrand(String setBrand) { this.setBrand = setBrand; }
    public Integer getPickedGroupId() { return pickedGroupId; }
    public void setPickedGroupId(Integer pickedGroupId) { this.pickedGroupId = pickedGroupId; }
}
