package com.hmdm.persistence.domain;

import java.io.Serializable;

/** A configuration-owned explicit runtime-permission override for one assigned application. */
public class ManagedAppPermission implements Serializable {
    private Integer applicationId;
    private String permission;
    private String state;
    public Integer getApplicationId() { return applicationId; }
    public void setApplicationId(Integer applicationId) { this.applicationId = applicationId; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
}
