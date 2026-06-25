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

import com.hmdm.persistence.domain.AgentRollout;
import com.hmdm.persistence.domain.RolloutDeviceRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** MyBatis mapper for staged agent-APK rollouts. Auto-registered via the mapper package scan. */
public interface RolloutMapper {

    @Insert({"INSERT INTO agentRollout (customerId, targetVersion, packageName, apkUrl, apkSha256, apkVersionCode, stage, createdAt, updatedAt) " +
            "VALUES (#{customerId}, #{targetVersion}, #{packageName}, #{apkUrl}, #{apkSha256}, #{apkVersionCode}, #{stage}, #{createdAt}, #{updatedAt})"})
    @SelectKey(statement = "SELECT currval('agentrollout_id_seq')", keyColumn = "id", keyProperty = "id",
            before = false, resultType = int.class)
    void insertRollout(AgentRollout rollout);

    @Insert({"INSERT INTO agentRolloutCanary (rolloutId, deviceNumber) VALUES (#{rolloutId}, #{deviceNumber}) " +
            "ON CONFLICT DO NOTHING"})
    void insertCanary(@Param("rolloutId") int rolloutId, @Param("deviceNumber") String deviceNumber);

    @Select({"SELECT * FROM agentRollout WHERE customerId = #{customerId} AND stage IN ('canary','fleet') ORDER BY id DESC LIMIT 1"})
    AgentRollout findActiveByCustomer(@Param("customerId") int customerId);

    @Select({"SELECT * FROM agentRollout WHERE id = #{id}"})
    AgentRollout findById(@Param("id") int id);

    @Update({"UPDATE agentRollout SET stage = #{stage}, updatedAt = #{updatedAt} WHERE id = #{id}"})
    void updateStage(@Param("id") int id, @Param("stage") String stage, @Param("updatedAt") long updatedAt);

    @Select({"SELECT deviceNumber FROM agentRolloutCanary WHERE rolloutId = #{rolloutId}"})
    List<String> listCanaryNumbers(@Param("rolloutId") int rolloutId);

    @Select({"SELECT d.number AS deviceNumber, s.agentVersion AS agentVersion, d.agentCapabilities AS capabilitiesJson " +
            "FROM devices d LEFT JOIN device_state s ON s.deviceNumber = d.number WHERE d.customerId = #{customerId}"})
    List<RolloutDeviceRow> listCustomerDevices(@Param("customerId") int customerId);

    @Select({"SELECT DISTINCT c.deviceNumber FROM agentCommand c JOIN devices d ON d.number = c.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND c.type = 'app.install' AND c.status IN ('pending','delivered')"})
    List<String> listPendingInstallNumbers(@Param("customerId") int customerId);
}
