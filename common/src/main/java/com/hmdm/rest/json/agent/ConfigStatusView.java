/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
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
    /**
     * The kiosk background image the device's configuration specifies ({@code kiosk.theme.backgroundImage} of the
     * document it is sent); null without one. Not what the device shows: the agent doesn't report that yet.
     */
    private DesiredKioskBackgroundImage kioskBackgroundImage;

    /** Sets {@link #kioskBackgroundImage} from the configuration's document (null: none). */
    public void setKioskBackgroundImageFrom(DesiredConfig doc) {
        DesiredKiosk kiosk = doc == null ? null : doc.getKiosk();
        DesiredKioskTheme theme = kiosk == null ? null : kiosk.getTheme();
        this.kioskBackgroundImage = theme == null ? null : theme.getBackgroundImage();
    }

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
