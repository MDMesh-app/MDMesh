package com.hmdm.persistence.domain;

import java.io.Serializable;

/** An agent lifecycle event (boot, app install/uninstall, command result, connectivity, etc.). */
public class DeviceEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private String deviceNumber;
    private String type;
    private Long ts;
    private String detail;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getDeviceNumber() { return deviceNumber; }
    public void setDeviceNumber(String v) { this.deviceNumber = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public Long getTs() { return ts; }
    public void setTs(Long v) { this.ts = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
}
