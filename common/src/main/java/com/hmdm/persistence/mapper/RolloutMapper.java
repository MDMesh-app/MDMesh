/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.AgentCommand;
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

    /** Skips a number no device has any more (deleted since the canary set was read): the row would break its
     *  foreign key to devices. */
    @Insert({"INSERT INTO agentRolloutCanary (rolloutId, deviceNumber) SELECT #{rolloutId}, #{deviceNumber} " +
            "WHERE EXISTS (SELECT 1 FROM devices WHERE number = #{deviceNumber}) ON CONFLICT DO NOTHING"})
    void insertCanary(@Param("rolloutId") int rolloutId, @Param("deviceNumber") String deviceNumber);

    @Select({"SELECT * FROM agentRollout WHERE customerId = #{customerId} AND stage IN ('canary','fleet') ORDER BY id DESC LIMIT 1"})
    AgentRollout findActiveByCustomer(@Param("customerId") int customerId);

    @Select({"SELECT * FROM agentRollout WHERE id = #{id}"})
    AgentRollout findById(@Param("id") int id);

    /** Moves the rollout to {@code to} only while it is still in {@code from}, so a concurrent promote, cancel or
     *  completion that got there first is never overwritten. @return rows changed, 0 or 1. */
    @Update({"UPDATE agentRollout SET stage = #{to}, updatedAt = #{updatedAt} WHERE id = #{id} AND stage = #{from}"})
    int moveStage(@Param("id") int id, @Param("from") String from, @Param("to") String to,
                  @Param("updatedAt") long updatedAt);

    /** Cancels the rollout while it is still running; a done or cancelled one keeps its stage. @return rows changed. */
    @Update({"UPDATE agentRollout SET stage = 'cancelled', updatedAt = #{updatedAt} " +
            "WHERE id = #{id} AND stage IN ('canary','fleet')"})
    int cancelActive(@Param("id") int id, @Param("updatedAt") long updatedAt);

    @Select({"SELECT deviceNumber FROM agentRolloutCanary WHERE rolloutId = #{rolloutId}"})
    List<String> listCanaryNumbers(@Param("rolloutId") int rolloutId);

    /** The customer's devices a rollout counts. Not wiped ones: they never check in again, so they'd never finish. */
    @Select({"SELECT d.number AS deviceNumber, s.agentVersion AS agentVersion, d.agentCapabilities AS capabilitiesJson " +
            "FROM devices d LEFT JOIN device_state s ON s.deviceNumber = d.number WHERE d.customerId = #{customerId} " +
            "AND d.wipedAt IS NULL"})
    List<RolloutDeviceRow> listCustomerDevices(@Param("customerId") int customerId);

    @Select({"SELECT c.* FROM agentCommand c JOIN devices d ON d.number = c.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND c.type = 'app.install' AND c.status IN ('pending','delivered')"})
    List<AgentCommand> listInFlightInstalls(@Param("customerId") int customerId);

    @Select({"SELECT d.number AS deviceNumber, s.agentVersion AS agentVersion, d.agentCapabilities AS capabilitiesJson " +
            "FROM devices d LEFT JOIN device_state s ON s.deviceNumber = d.number WHERE d.number = #{deviceNumber}"})
    RolloutDeviceRow findDeviceRow(@Param("deviceNumber") String deviceNumber);

    @Select({"SELECT COUNT(*) > 0 FROM agentRolloutCanary WHERE rolloutId = #{rolloutId} AND deviceNumber = #{deviceNumber}"})
    boolean isCanary(@Param("rolloutId") int rolloutId, @Param("deviceNumber") String deviceNumber);

    /** Queues {@code cmd} unless the same command is already pending or delivered to the device. One statement closes
     *  almost all of the window between a check-in re-queue and a create/promote loop; two transactions running at the
     *  same instant can still both pass NOT EXISTS (READ COMMITTED), and the worst case is one duplicate install that
     *  the agent's version check skips once the first has installed. @return rows inserted, 0 or 1. */
    @Insert({"INSERT INTO agentCommand (deviceNumber, type, payload, requiresCapability, status, createdAt) " +
            "SELECT #{deviceNumber}, #{type}, #{payload}, #{requiresCapability}, #{status}, #{createdAt} " +
            "WHERE NOT EXISTS (SELECT 1 FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND type = #{type} " +
            "AND payload = #{payload} AND status IN ('pending','delivered'))"})
    int insertIfNotInFlight(AgentCommand cmd);

    @Select({"SELECT * FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND type = 'app.install' " +
            "AND createdAt >= #{since} ORDER BY id DESC"})
    List<AgentCommand> listInstallsSince(@Param("deviceNumber") String deviceNumber, @Param("since") long since);
}
