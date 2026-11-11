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
 * <p>MyBatis mapper for {@link AgentEnrollmentToken} (agent v1 enrollment tokens).</p>
 */
public interface AgentEnrollmentTokenMapper {

    @Insert({"INSERT INTO agentEnrollmentToken (token, customerId, used, createdAt, expiresAt, configurationId, " +
            "maxUses, useCount) " +
            "VALUES (#{token}, #{customerId}, #{used}, #{createdAt}, #{expiresAt}, #{configurationId}, " +
            "#{maxUses}, #{useCount})"})
    @SelectKey(statement = "SELECT currval('agentenrollmenttoken_id_seq')", keyColumn = "id", keyProperty = "id",
            before = false, resultType = int.class)
    void insert(AgentEnrollmentToken token);

    @Select({"SELECT * FROM agentEnrollmentToken WHERE token = #{token}"})
    AgentEnrollmentToken findByToken(@Param("token") String token);

    /**
     * Atomically claim one use of the token: of N concurrent enrolls, exactly as many get rowcount 1
     * as the token has uses left; the rest get 0 (no use left, or expired between the pre-check and
     * here). This is the guard that keeps N concurrent enrolls with a token for M devices from
     * creating more than M device rows. {@code used} turns true with the last use (the SET reads the
     * row before the update).
     */
    @Update({"UPDATE agentEnrollmentToken SET useCount = useCount + 1, used = (useCount + 1 >= maxUses) " +
            "WHERE id = #{id} AND used = false AND useCount < maxUses AND revokedAt IS NULL " +
            "AND (expiresAt IS NULL OR expiresAt > #{now})"})
    int claim(@Param("id") Integer id, @Param("now") long now);

    /**
     * For the rest of the current transaction, wait at most 15 s for a lock: an enroll that can't get its token's lock
     * in that time fails (retryably) instead of holding a request thread.
     */
    @Update({"SET LOCAL lock_timeout = '15s'"})
    void setEnrollLockTimeout();

    /**
     * Locks the token row until the transaction ends, so enrollments with one token run one after another (a retry
     * waits for the request it retries). Null when the token is gone.
     */
    @Select({"SELECT id FROM agentEnrollmentToken WHERE id = #{id} FOR UPDATE"})
    Integer lockForEnroll(@Param("id") Integer id);

    /**
     * Stop every token of the customer that has not expired, used-up ones included. One UPDATE: a claim that races it
     * either took its use before (that device enrolls) or finds the row stopped.
     */
    @Update({"UPDATE agentEnrollmentToken SET revokedAt = #{now} " +
            "WHERE customerId = #{customerId} AND revokedAt IS NULL AND (expiresAt IS NULL OR expiresAt > #{now})"})
    int revokeAll(@Param("customerId") int customerId, @Param("now") long now);
}
