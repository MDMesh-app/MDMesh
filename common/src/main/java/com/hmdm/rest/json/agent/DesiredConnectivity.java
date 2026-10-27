/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * Connectivity section of {@link DesiredConfig} (protocol 1.5): how the agent holds its live connection, the same
 * modes as the per-device {@code device.powerMode} command. {@code adaptive} = only while the screen is on or
 * charging (battery-saver); {@code alwaysOn} = 24/7, so commands reach a locked device within seconds.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredConnectivity {
    private String mode;
}
