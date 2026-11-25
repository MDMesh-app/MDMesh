/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>Describes the physical device (capability-matrix {@code device} block). On enrollment the
 * {@code id} is absent (the server issues it); on check-in it carries the server-issued id.</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentDeviceInfo {

    private String id;

    private Integer androidSdkInt;

    private String androidRelease;

    private String manufacturer;

    private String model;

    private Boolean isDeviceOwner;

    public AgentDeviceInfo() {
    }
}
