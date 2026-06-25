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
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper;

/**
 * <p>DAO for the agent v1 single-use enrollment tokens.</p>
 */
@Singleton
public class AgentEnrollmentTokenDAO {

    private final AgentEnrollmentTokenMapper mapper;

    @Inject
    public AgentEnrollmentTokenDAO(AgentEnrollmentTokenMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(AgentEnrollmentToken token) {
        mapper.insert(token);
    }

    public AgentEnrollmentToken findByToken(String token) {
        return mapper.findByToken(token);
    }

    public void markUsed(Integer id) {
        mapper.markUsed(id);
    }
}
