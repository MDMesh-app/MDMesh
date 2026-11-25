/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.rest.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * The body of a request that only needs the caller's current password, such as deleting an account:
 * {@code {"oldPassword": "<MD5 of the password, in hex>"}}.
 */
@ApiModel(description = "The caller's current password")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CurrentPassword {

    @ApiModelProperty("MD5 of the caller's current password, in hex")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String oldPassword;

    public String getOldPassword() {
        return oldPassword;
    }

    public void setOldPassword(String oldPassword) {
        this.oldPassword = oldPassword;
    }

    /** Never the password. */
    @Override
    public String toString() {
        return "CurrentPassword{}";
    }
}
