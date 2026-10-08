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

package com.hmdm.event;

import lombok.Data;
import lombok.ToString;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * <p>An event fired when info for configuration was updated.</p>
 */
@Data
@ToString
public class ConfigurationUpdatedEvent implements Event, Serializable {

    /**
     * <p>An unique identifier of the configuration.</p>
     */
    private final int configurationId;

    /** Install-marked application versions newly introduced by this edit. */
    private final Set<Integer> addedInstallVersionIds;

    public ConfigurationUpdatedEvent(int configurationId) {
        this(configurationId, Collections.<Integer>emptySet());
    }

    public ConfigurationUpdatedEvent(int configurationId, Set<Integer> addedInstallVersionIds) {
        this.configurationId = configurationId;
        this.addedInstallVersionIds = addedInstallVersionIds == null || addedInstallVersionIds.isEmpty()
                ? Collections.<Integer>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(addedInstallVersionIds));
    }

    /**
     * <p>Gets the type of the event.</p>
     *
     * @return a type of the event.
     */
    @Override
    public EventType getType() {
        return EventType.CONFIGURATION_UPDATED;
    }
}
