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

package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.AgentEnrollmentToken;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;

/**
 * <p>MyBatis mapper for {@link AgentEnrollmentToken} (agent v1 single-use enrollment tokens).</p>
 */
public interface AgentEnrollmentTokenMapper {

    @Insert({"INSERT INTO agentEnrollmentToken (token, customerId, used, createdAt, expiresAt) " +
            "VALUES (#{token}, #{customerId}, #{used}, #{createdAt}, #{expiresAt})"})
    @SelectKey(statement = "SELECT currval('agentenrollmenttoken_id_seq')", keyColumn = "id", keyProperty = "id",
            before = false, resultType = int.class)
    void insert(AgentEnrollmentToken token);

    @Select({"SELECT * FROM agentEnrollmentToken WHERE token = #{token}"})
    AgentEnrollmentToken findByToken(@Param("token") String token);

    @Update({"UPDATE agentEnrollmentToken SET used = true WHERE id = #{id}"})
    void markUsed(@Param("id") Integer id);
}
