/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.persistence.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/**
 * <p>An enrollment token for the from-scratch Android agent v1 protocol. Minted by an authenticated
 * admin and carried into the device through the QR provisioning bundle. It enrolls up to
 * {@link #getMaxUses()} devices (one by default) before it expires; {@link #isUsed()} turns true once
 * the last of them has enrolled.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentEnrollmentToken implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer id;
    private String token;
    private int customerId;
    private boolean used;
    private Long createdAt;
    private Long expiresAt;
    /** Configuration the enrolled device is bound to; NULL = the customer's settings default. */
    private Integer configurationId;
    /** How many devices the token may enroll; NULL in a mint request means 1 (single use). */
    private Integer maxUses;
    /** How many devices have enrolled with it so far. */
    private int useCount;
    /** Mint request only: how long the token is valid, in hours (EnrollmentTokenLimits). Not stored. */
    private Integer validHours;

    public AgentEnrollmentToken() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getCustomerId() {
        return customerId;
    }

    public void setCustomerId(int customerId) {
        this.customerId = customerId;
    }

    public boolean isUsed() {
        return used;
    }

    public void setUsed(boolean used) {
        this.used = used;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getConfigurationId() {
        return configurationId;
    }

    public void setConfigurationId(Integer configurationId) {
        this.configurationId = configurationId;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Integer getMaxUses() {
        return maxUses;
    }

    public void setMaxUses(Integer maxUses) {
        this.maxUses = maxUses;
    }

    public int getUseCount() {
        return useCount;
    }

    public void setUseCount(int useCount) {
        this.useCount = useCount;
    }

    public Integer getValidHours() {
        return validHours;
    }

    public void setValidHours(Integer validHours) {
        this.validHours = validHours;
    }
}
