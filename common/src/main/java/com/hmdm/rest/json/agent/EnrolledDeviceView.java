/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hmdm.persistence.domain.Device;
import lombok.Getter;

/** A device the Enroll page shows arriving: just what it needs. */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnrolledDeviceView {
    private final String number;
    private final String description;
    /** Server time, ms. */
    private final Long enrollTime;
    /** Server time, ms: equal to enrollTime until the device's first check-in. */
    private final Long lastUpdate;

    public EnrolledDeviceView(Device d) {
        this.number = d.getNumber();
        this.description = d.getDescription();
        this.enrollTime = d.getEnrollTime();
        this.lastUpdate = d.getLastUpdate();
    }
}
