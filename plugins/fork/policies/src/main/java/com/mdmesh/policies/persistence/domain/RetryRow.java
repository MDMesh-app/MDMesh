package com.mdmesh.policies.persistence.domain;

/** A job command that expired without ever reaching its phone, with what is needed to send it again. */
public class RetryRow {
    private Integer linkId;
    private Integer jobId;
    private Integer attempts;
    private String deviceNumber;
    private String type;
    private String payload;
    private String requiresCapability;

    public Integer getLinkId() { return linkId; }
    public void setLinkId(Integer linkId) { this.linkId = linkId; }
    public Integer getJobId() { return jobId; }
    public void setJobId(Integer jobId) { this.jobId = jobId; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public String getDeviceNumber() { return deviceNumber; }
    public void setDeviceNumber(String deviceNumber) { this.deviceNumber = deviceNumber; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getRequiresCapability() { return requiresCapability; }
    public void setRequiresCapability(String requiresCapability) { this.requiresCapability = requiresCapability; }
}
