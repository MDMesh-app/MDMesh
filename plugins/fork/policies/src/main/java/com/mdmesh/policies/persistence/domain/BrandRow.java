package com.mdmesh.policies.persistence.domain;

/** A brand made by hand (its key, as phones' brands are keyed, and how it is shown), or a brand set on a phone. */
public class BrandRow {
    private String brand;
    private String label;
    private Integer deviceId;

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Integer getDeviceId() { return deviceId; }
    public void setDeviceId(Integer deviceId) { this.deviceId = deviceId; }
}
