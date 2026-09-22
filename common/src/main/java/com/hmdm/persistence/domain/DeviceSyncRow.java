package com.hmdm.persistence.domain;

import lombok.Getter;
import lombok.Setter;

/**
 * One row per device of a customer, joined with its last-reported applied config revision — used
 * to reconcile desired state against what each device last acknowledged.
 */
@Getter
@Setter
public class DeviceSyncRow {
    private String deviceNumber;
    private Integer configurationId;
    private String capabilitiesJson;
    private String appliedConfigRevision;
}
