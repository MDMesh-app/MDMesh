package com.mdmesh.policies.persistence.domain;

/** How many of a job's phones (by wake state) or commands (by status) are in one state. */
public class JobCount {
    private Integer jobId;
    private String state;
    private Integer count;

    public Integer getJobId() { return jobId; }
    public void setJobId(Integer jobId) { this.jobId = jobId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }
}
