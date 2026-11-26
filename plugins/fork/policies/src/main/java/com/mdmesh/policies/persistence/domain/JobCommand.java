package com.mdmesh.policies.persistence.domain;

/**
 * A command a job queues, in core's agentCommand columns. {@code status} is "pending", or "failed" with a
 * {@code detail} for an install core refused to send (core records those as failed commands too).
 */
public class JobCommand {
    /** Set by a single insert (retries); batch inserts don't fill it. */
    private Integer id;
    private String deviceNumber;
    private String type;
    private String payload;
    private String requiresCapability;
    private String status = "pending";
    private Long createdAt;
    private Long completedAt;
    private String detail;

    public JobCommand() {
    }

    public JobCommand(String deviceNumber, String type, String payload, String requiresCapability, long createdAt) {
        this.deviceNumber = deviceNumber;
        this.type = type;
        this.payload = payload;
        this.requiresCapability = requiresCapability;
        this.createdAt = createdAt;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getDeviceNumber() { return deviceNumber; }
    public void setDeviceNumber(String deviceNumber) { this.deviceNumber = deviceNumber; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getRequiresCapability() { return requiresCapability; }
    public void setRequiresCapability(String requiresCapability) { this.requiresCapability = requiresCapability; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getCompletedAt() { return completedAt; }
    public void setCompletedAt(Long completedAt) { this.completedAt = completedAt; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
