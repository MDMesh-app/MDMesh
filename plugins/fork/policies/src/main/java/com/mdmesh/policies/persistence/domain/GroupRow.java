package com.mdmesh.policies.persistence.domain;

/** A device group: its name and the policy (configuration) its phones should be on, or none yet. */
public class GroupRow {
    private Integer id;
    private int customerId;
    private String name;
    private Integer configurationId;
    private long createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public int getCustomerId() { return customerId; }
    public void setCustomerId(int customerId) { this.customerId = customerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getConfigurationId() { return configurationId; }
    public void setConfigurationId(Integer configurationId) { this.configurationId = configurationId; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
