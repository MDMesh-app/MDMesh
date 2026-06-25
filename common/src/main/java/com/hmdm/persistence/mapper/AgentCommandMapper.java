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

import com.hmdm.persistence.domain.AgentCommand;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>MyBatis mapper for the opaque agent v1 command queue ({@link AgentCommand}). The server never
 * interprets {@code type}/{@code payload}; this mapper only stores and forwards them.</p>
 */
public interface AgentCommandMapper {

    @Insert({"INSERT INTO agentCommand (deviceNumber, type, payload, requiresCapability, status, createdAt, deliveredAt) " +
            "VALUES (#{deviceNumber}, #{type}, #{payload}, #{requiresCapability}, #{status}, #{createdAt}, #{deliveredAt})"})
    @SelectKey(statement = "SELECT currval('agentcommand_id_seq')", keyColumn = "id", keyProperty = "id",
            before = false, resultType = int.class)
    void insert(AgentCommand command);

    @Select({"SELECT * FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND status = 'pending' ORDER BY id"})
    List<AgentCommand> listPending(@Param("deviceNumber") String deviceNumber);

    @Update({"UPDATE agentCommand SET status = #{status}, deliveredAt = #{deliveredAt} WHERE id = #{id}"})
    void markStatus(@Param("id") Integer id, @Param("status") String status, @Param("deliveredAt") Long deliveredAt);

    @Update({"UPDATE agentCommand SET status = #{status}, detail = #{detail} WHERE id = #{id}"})
    void markStatusDetail(@Param("id") Integer id, @Param("status") String status, @Param("detail") String detail);

    @Select({"SELECT * FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND id = #{id}"})
    AgentCommand findByDeviceAndId(@Param("deviceNumber") String deviceNumber, @Param("id") Integer id);

    /**
     * Atomically claim a pending command for delivery. Returns 1 if THIS caller claimed it, 0 if a
     * concurrent check-in already did — so a command is delivered to exactly one check-in.
     */
    @Update({"UPDATE agentCommand SET status = 'delivered', deliveredAt = #{deliveredAt} " +
            "WHERE id = #{id} AND status = 'pending'"})
    int claimForDelivery(@Param("id") Integer id, @Param("deliveredAt") Long deliveredAt);

    /**
     * Record a terminal result, but only if the command isn't already terminal — so the first real
     * result wins and a late/duplicate ack can't overwrite a genuine done/failed (+ its detail).
     */
    @Update({"UPDATE agentCommand SET status = #{status}, detail = #{detail}, completedAt = #{completedAt} " +
            "WHERE id = #{id} AND status NOT IN ('done','failed','unsupported','expired')"})
    void markResultWithTime(@Param("id") Integer id, @Param("status") String status,
                            @Param("detail") String detail, @Param("completedAt") Long completedAt);

    @Update({"UPDATE agentCommand SET status = 'expired', completedAt = #{cutoff} " +
            "WHERE deviceNumber = #{deviceNumber} AND status IN ('pending','delivered') AND createdAt < #{cutoff}"})
    void expireStale(@Param("deviceNumber") String deviceNumber, @Param("cutoff") long cutoff);

    @Select({"SELECT * FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND createdAt >= #{since} " +
            "ORDER BY id DESC LIMIT #{limit}"})
    List<AgentCommand> listHistory(@Param("deviceNumber") String deviceNumber,
                                   @Param("since") long since, @Param("limit") int limit);
}
