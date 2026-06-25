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
 * <p>A single queued command for the from-scratch Android agent v1 protocol. {@code type} and
 * {@code payload} are stored verbatim and never interpreted by the server; delivery is gated only
 * by {@code requiresCapability} set-membership against the device's flattened capability tokens.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer id;
    private String deviceNumber;
    private String type;
    private String payload;
    private String requiresCapability;
    private String status;
    private Long createdAt;
    private Long deliveredAt;
    private Long completedAt;
    /** Device-reported result detail (e.g. a command's JSON output). Mapped from SELECT * so the
     *  command-history endpoint exposes it; without this field MyBatis silently drops the column. */
    private String detail;

    public AgentCommand() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getDeviceNumber() {
        return deviceNumber;
    }

    public void setDeviceNumber(String deviceNumber) {
        this.deviceNumber = deviceNumber;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getRequiresCapability() {
        return requiresCapability;
    }

    public void setRequiresCapability(String requiresCapability) {
        this.requiresCapability = requiresCapability;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Long deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Long getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Long completedAt) {
        this.completedAt = completedAt;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
