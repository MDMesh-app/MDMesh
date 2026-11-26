package com.mdmesh.policies.persistence.domain;

/**
 * One phone as {@code FleetMapper.listFleet} reads it: its row, its agent state, its latest config.apply and its
 * latest finished app scan. Plain getters and setters (MyBatis maps the columns by name, ignoring case).
 */
public class FleetRow {
    private Integer id;
    private String number;
    private Integer configurationId;
    private String capabilities;
    private String telemetry;
    private String agentVersion;
    private String appliedRevision;
    private Long appliedAt;
    private Integer applyId;
    private String applyStatus;
    private String applyDetail;
    private Long applyCreatedAt;
    private Long applyCompletedAt;
    private String scanDetail;
    private Long scannedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public Integer getConfigurationId() { return configurationId; }
    public void setConfigurationId(Integer configurationId) { this.configurationId = configurationId; }
    public String getCapabilities() { return capabilities; }
    public void setCapabilities(String capabilities) { this.capabilities = capabilities; }
    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }
    public String getTelemetry() { return telemetry; }
    public void setTelemetry(String telemetry) { this.telemetry = telemetry; }
    public String getAppliedRevision() { return appliedRevision; }
    public void setAppliedRevision(String appliedRevision) { this.appliedRevision = appliedRevision; }
    public Long getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Long appliedAt) { this.appliedAt = appliedAt; }
    public Integer getApplyId() { return applyId; }
    public void setApplyId(Integer applyId) { this.applyId = applyId; }
    public String getApplyStatus() { return applyStatus; }
    public void setApplyStatus(String applyStatus) { this.applyStatus = applyStatus; }
    public String getApplyDetail() { return applyDetail; }
    public void setApplyDetail(String applyDetail) { this.applyDetail = applyDetail; }
    public Long getApplyCreatedAt() { return applyCreatedAt; }
    public void setApplyCreatedAt(Long applyCreatedAt) { this.applyCreatedAt = applyCreatedAt; }
    public Long getApplyCompletedAt() { return applyCompletedAt; }
    public void setApplyCompletedAt(Long applyCompletedAt) { this.applyCompletedAt = applyCompletedAt; }
    public String getScanDetail() { return scanDetail; }
    public void setScanDetail(String scanDetail) { this.scanDetail = scanDetail; }
    public Long getScannedAt() { return scannedAt; }
    public void setScannedAt(Long scannedAt) { this.scannedAt = scannedAt; }
}
