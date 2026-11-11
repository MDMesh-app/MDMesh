/*
 * MDMesh: an immutable, server-managed kiosk background image descriptor.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * A hosted image the agent may download for the kiosk launcher. The checksum pins the exact bytes
 * selected by the administrator; agents must retain the color-only theme when it is absent or invalid.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredKioskBackgroundImage {
    private String url;
    private String sha256;
    /** Protocol 1.10: {@code fit} (whole, letterboxed); absent = fill (cropped). Older agents ignore it and fill. */
    private String fit;
    /** Protocol 1.10: the focal point a fill crop keeps in view, 0..1 of the image; absent = the centre. */
    private Double focalX;
    private Double focalY;
}
