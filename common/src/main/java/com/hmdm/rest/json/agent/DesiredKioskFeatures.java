/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * Feature toggles for {@link DesiredKiosk}: which system UI surfaces stay reachable in kiosk mode.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredKioskFeatures {
    private Boolean home;
    private Boolean recents;
    private Boolean notifications;
    private Boolean systemInfo;
    private Boolean keyguard;
    private Boolean lockButtons;
    /** Protocol 1.4: block volume changes while in kiosk (the platform mutes the device meanwhile). */
    private Boolean lockVolume;
}
