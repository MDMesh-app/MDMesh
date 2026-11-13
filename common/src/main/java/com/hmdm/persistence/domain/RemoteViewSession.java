package com.hmdm.persistence.domain;

import java.util.UUID;

/** Durable metadata for a consented remote view; intentionally contains no signaling or media. */
public class RemoteViewSession {
    private UUID id;
    private String deviceNumber;
    private Integer requesterUserId;
    private String mode;
    private String state;
    private String agentJoinSecretHash;
    private Long createdAt;
    private Long consentExpiresAt;
    private Long expiresAt;
    private Long endedAt;
    private String endReason;
    public UUID getId() { return id; } public void setId(UUID v) { id = v; }
    public String getDeviceNumber() { return deviceNumber; } public void setDeviceNumber(String v) { deviceNumber = v; }
    public Integer getRequesterUserId() { return requesterUserId; } public void setRequesterUserId(Integer v) { requesterUserId = v; }
    public String getMode() { return mode; } public void setMode(String v) { mode = v; }
    public String getState() { return state; } public void setState(String v) { state = v; }
    public String getAgentJoinSecretHash() { return agentJoinSecretHash; } public void setAgentJoinSecretHash(String v) { agentJoinSecretHash = v; }
    public Long getCreatedAt() { return createdAt; } public void setCreatedAt(Long v) { createdAt = v; }
    public Long getConsentExpiresAt() { return consentExpiresAt; } public void setConsentExpiresAt(Long v) { consentExpiresAt = v; }
    public Long getExpiresAt() { return expiresAt; } public void setExpiresAt(Long v) { expiresAt = v; }
    public Long getEndedAt() { return endedAt; } public void setEndedAt(Long v) { endedAt = v; }
    public String getEndReason() { return endReason; } public void setEndReason(String v) { endReason = v; }
}
