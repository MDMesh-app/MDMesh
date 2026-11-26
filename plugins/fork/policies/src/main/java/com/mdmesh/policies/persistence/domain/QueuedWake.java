package com.mdmesh.policies.persistence.domain;

/** A job phone waiting for its wake ({@code JobMapper.nextQueued}). */
public class QueuedWake {
    private Integer id;
    private Integer jobId;
    private String deviceNumber;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getJobId() { return jobId; }
    public void setJobId(Integer jobId) { this.jobId = jobId; }
    public String getDeviceNumber() { return deviceNumber; }
    public void setDeviceNumber(String deviceNumber) { this.deviceNumber = deviceNumber; }
}
