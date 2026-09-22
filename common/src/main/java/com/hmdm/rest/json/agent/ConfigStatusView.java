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

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * Desired-state configuration status for a single device: the configuration's current revision vs.
 * the revision the agent last reported as applied.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConfigStatusView {
    private Integer configurationId;
    private String currentRevision;
    private String appliedRevision;
    private Long appliedAt;
    private boolean inSync;
    private boolean supported;
    private LastCommand lastCommand;

    /** Slim view of the latest config.apply — deliberately omits the payload (it embeds the kiosk password). */
    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LastCommand {
        private Integer id;
        private String status;
        private String detail;
        private Long createdAt;
        private Long completedAt;

        public static LastCommand from(com.hmdm.persistence.domain.AgentCommand c) {
            if (c == null) return null;
            LastCommand v = new LastCommand();
            v.setId(c.getId());
            v.setStatus(c.getStatus());
            v.setDetail(c.getDetail());
            v.setCreatedAt(c.getCreatedAt());
            v.setCompletedAt(c.getCompletedAt());
            return v;
        }
    }
}
