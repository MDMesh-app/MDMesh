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

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.AgentRollout;
import com.hmdm.persistence.domain.RolloutDeviceRow;
import com.hmdm.persistence.mapper.RolloutMapper;

import java.util.List;

/** DAO for staged agent-APK rollouts. Thin wrapper over {@link RolloutMapper}. */
@Singleton
public class RolloutDAO {

    private final RolloutMapper mapper;

    @Inject
    public RolloutDAO(RolloutMapper mapper) {
        this.mapper = mapper;
    }

    public void insertRollout(AgentRollout rollout) {
        mapper.insertRollout(rollout);
    }

    public void insertCanary(int rolloutId, String deviceNumber) {
        mapper.insertCanary(rolloutId, deviceNumber);
    }

    public AgentRollout findActiveByCustomer(int customerId) {
        return mapper.findActiveByCustomer(customerId);
    }

    public AgentRollout findById(int id) {
        return mapper.findById(id);
    }

    public void updateStage(int id, String stage) {
        mapper.updateStage(id, stage, System.currentTimeMillis());
    }

    public List<String> listCanaryNumbers(int rolloutId) {
        return mapper.listCanaryNumbers(rolloutId);
    }

    public List<RolloutDeviceRow> listCustomerDevices(int customerId) {
        return mapper.listCustomerDevices(customerId);
    }

    /** Device numbers in this customer with an in-flight (pending/delivered) app.install. */
    public List<String> listPendingInstallNumbers(int customerId) {
        return mapper.listPendingInstallNumbers(customerId);
    }
}
