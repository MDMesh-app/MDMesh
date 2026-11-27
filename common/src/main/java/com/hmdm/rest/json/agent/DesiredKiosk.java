/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

/**
 * Kiosk section of {@link DesiredConfig}: mirrors the agent's KioskApplyPayload.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredKiosk {
    private String mode;
    private List<String> allowedPackages;
    private String pinPackage;
    private DesiredKioskFeatures features;
    private String exitMode;
    private String password;
    private DesiredKioskTheme theme;
}
