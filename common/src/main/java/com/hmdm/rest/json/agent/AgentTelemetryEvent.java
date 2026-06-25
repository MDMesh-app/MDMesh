package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Wire DTO for a buffered agent lifecycle event flushed on check-in. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentTelemetryEvent {
    private String type;
    private Long ts;
    private String detail;

    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public Long getTs() { return ts; }
    public void setTs(Long v) { this.ts = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
}
