/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * System update section of {@link DesiredConfig} (protocol 1.8): how the device installs OS updates, applied with
 * {@code DevicePolicyManager.setSystemUpdatePolicy}. {@code automatic} = as soon as an update is available;
 * {@code windowed} = only between {@link #windowStart} and {@link #windowEnd}, minutes after the device's local
 * midnight (0..1439, different, the window may cross midnight); {@code postpone} = up to 30 days (device makers or
 * carriers might exempt important security updates).
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredSystemUpdate {
    private String policy;
    private Integer windowStart;
    private Integer windowEnd;
}
