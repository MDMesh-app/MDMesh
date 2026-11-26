package com.mdmesh.policies.persistence.domain;

/** A phone a job may act on: accessible to the user who creates the job ({@code JobMapper.listAccessible}). */
public class JobPhone {
    private Integer id;
    private String number;
    private Integer configurationId;
    private Integer customerId;
    /** The agent version the phone last reported (null when unknown, or not read by the query). */
    private String agentVersion;
    /** When core marked the phone wiped (it erased itself); null when it wasn't. */
    private Long wipedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public Integer getConfigurationId() { return configurationId; }
    public void setConfigurationId(Integer configurationId) { this.configurationId = configurationId; }
    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }
    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }
    public Long getWipedAt() { return wipedAt; }
    public void setWipedAt(Long wipedAt) { this.wipedAt = wipedAt; }
}
