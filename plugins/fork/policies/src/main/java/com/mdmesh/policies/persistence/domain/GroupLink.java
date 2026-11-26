package com.mdmesh.policies.persistence.domain;

/** One member of a group: a brand it covers, or a phone (device id) picked into it. */
public class GroupLink {
    private int groupId;
    private String brand;
    private Integer deviceId;

    public int getGroupId() { return groupId; }
    public void setGroupId(int groupId) { this.groupId = groupId; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public Integer getDeviceId() { return deviceId; }
    public void setDeviceId(Integer deviceId) { this.deviceId = deviceId; }
}
