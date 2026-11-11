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

import com.hmdm.persistence.domain.DeviceLocation;
import com.hmdm.persistence.domain.DeviceSyncRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>Minimal mapper for the agent v1 capability column on the {@code devices} table. Kept separate
 * from {@link DeviceMapper} so the agent feature touches the shared device mapper as little as
 * possible.</p>
 */
public interface AgentDeviceMapper {

    @Update({"UPDATE devices SET agentCapabilities = #{capabilitiesJson} WHERE number = #{deviceNumber}"})
    void updateDeviceCapabilities(@Param("deviceNumber") String deviceNumber,
                                  @Param("capabilitiesJson") String capabilitiesJson);

    @Select({"SELECT agentCapabilities FROM devices WHERE number = #{deviceNumber}"})
    String getDeviceCapabilities(@Param("deviceNumber") String deviceNumber);

    @Select({"SELECT agentSecretHash FROM devices WHERE number = #{deviceNumber}"})
    String getDeviceSecretHash(@Param("deviceNumber") String deviceNumber);

    /**
     * Completes an agent enrollment, inside its transaction ({@link com.hmdm.persistence.AgentEnrollmentDAO}): the
     * secret hash that lets the device authenticate, its capabilities, its hardware id (an absent one keeps what is
     * there), last-seen and the enrollment time (kept when already set), and the attempt that may resume it.
     *
     * @return rows updated: 1, or 0 when the device row is gone
     */
    @Update({"UPDATE devices SET agentSecretHash = #{secretHash}, agentCapabilities = #{capabilitiesJson}, " +
            "hardwareid = COALESCE(#{hardwareId}, hardwareid), lastUpdate = #{ts}, " +
            "enrollTime = COALESCE(enrollTime, #{ts}), agentEnrollAttemptHash = #{attemptHash} " +
            "WHERE number = #{deviceNumber}"})
    int completeEnrollment(@Param("deviceNumber") String deviceNumber,
                           @Param("secretHash") String secretHash,
                           @Param("capabilitiesJson") String capabilitiesJson,
                           @Param("hardwareId") String hardwareId,
                           @Param("attemptHash") String attemptHash,
                           @Param("ts") long ts);

    /**
     * The device an enroll attempt created, while that enrollment can still be retried: completed (it has a secret),
     * not checked in since ({@link #markCheckedIn} clears the hash) and enrolled at or after {@code since}.
     */
    @Select({"SELECT number FROM devices WHERE agentEnrollAttemptHash = #{attemptHash} AND enrollTime >= #{since} " +
            "AND agentSecretHash IS NOT NULL LIMIT 1"})
    String findEnrollRetry(@Param("attemptHash") String attemptHash, @Param("since") long since);

    /**
     * New secret for a retried enrollment; only while the attempt still owns the device (no check-in yet). Last-seen is
     * left at the enrollment time: the device has not checked in.
     */
    @Update({"UPDATE devices SET agentSecretHash = #{secretHash} " +
            "WHERE number = #{deviceNumber} AND agentEnrollAttemptHash = #{attemptHash} AND agentSecretHash IS NOT NULL"})
    int reissueSecret(@Param("deviceNumber") String deviceNumber,
                      @Param("attemptHash") String attemptHash,
                      @Param("secretHash") String secretHash);

    /** The customer's device limit as the console applies it: 0 (none) for a master customer; null when not found. */
    @Select({"SELECT CASE WHEN master THEN 0 ELSE deviceLimit END FROM customers WHERE id = #{customerId}"})
    Integer customerDeviceLimit(@Param("customerId") int customerId);

    /**
     * Serialises the device-limit check of one customer's enrollments until the transaction ends: an advisory lock
     * (namespace 1296321861, "MDME"), so no row is locked and nothing else waits for it.
     */
    @Select({"SELECT 1 FROM (SELECT pg_advisory_xact_lock(1296321861, #{customerId})) AS locked"})
    Integer lockCustomerEnrollments(@Param("customerId") int customerId);

    /** The customer's devices, as on-demand creation counts them (UnsecureDAO.createNewDeviceOnDemand). */
    @Select({"SELECT COUNT(*) FROM devices WHERE customerId = #{customerId}"})
    long countCustomerDevices(@Param("customerId") int customerId);

    /** Whether the install has customers beyond the first: device limits apply only then, as for on-demand creation. */
    @Select({"SELECT EXISTS (SELECT 1 FROM customers WHERE id > 1)"})
    boolean hasSeveralCustomers();

    /**
     * The customer's devices enrolled at or after {@code since} that the user may see, newest first, at most
     * {@code limit} (the Enroll page). Access is filtered here, before the LIMIT, with the console's rule
     * ({@link DeviceMapper#USER_DEVICE_ACCESS}; a super admin sees all). Uses the (customerId, enrollTime) index.
     */
    @Select({"SELECT devices.id, devices.number, devices.description, devices.customerId, devices.enrollTime, " +
            "devices.lastUpdate FROM devices INNER JOIN users ON users.id = #{userId} " +
            "WHERE devices.customerId = #{customerId} AND devices.enrollTime >= #{since} " +
            "AND (#{superAdmin} OR " + DeviceMapper.USER_DEVICE_ACCESS + ") " +
            "ORDER BY devices.enrollTime DESC LIMIT #{limit}"})
    List<com.hmdm.persistence.domain.Device> listEnrolledSince(@Param("customerId") int customerId,
                                                              @Param("userId") int userId,
                                                              @Param("superAdmin") boolean superAdmin,
                                                              @Param("since") long since,
                                                              @Param("limit") int limit);

    /**
     * An authenticated check-in: last-seen, and the device has its credentials, so its enrollment can no longer be
     * retried.
     */
    @Update({"UPDATE devices SET lastUpdate = #{ts}, agentEnrollAttemptHash = NULL WHERE number = #{deviceNumber}"})
    void markCheckedIn(@Param("deviceNumber") String deviceNumber, @Param("ts") long ts);

    /**
     * The console's "Wiped": when the latest device.wipe was delivered. {@code ts} is also that command's deliveredAt,
     * which is how {@link #clearWipedForCommand} tells the wipe that set the mark from an earlier one.
     */
    @Update({"UPDATE devices SET wipedAt = #{ts} WHERE number = #{deviceNumber}"})
    void markWiped(@Param("deviceNumber") String deviceNumber, @Param("ts") long ts);

    /**
     * Clears "Wiped" when the result just reported for {@code commandId} is for the device.wipe that set the mark: one
     * of this device's, delivered no earlier than the mark. A late or repeated failure of an earlier wipe leaves a
     * later wipe's mark alone.
     */
    @Update({"UPDATE devices SET wipedAt = NULL WHERE number = #{deviceNumber} AND wipedAt IS NOT NULL " +
            "AND EXISTS (SELECT 1 FROM agentCommand c WHERE c.id = #{commandId} AND c.deviceNumber = #{deviceNumber} " +
            "AND c.type = 'device.wipe' AND c.deliveredAt >= devices.wipedAt)"})
    int clearWipedForCommand(@Param("deviceNumber") String deviceNumber, @Param("commandId") Integer commandId);

    /** Clears "Wiped" set before {@code cutoff}. */
    @Update({"UPDATE devices SET wipedAt = NULL WHERE number = #{deviceNumber} AND wipedAt < #{cutoff}"})
    int clearWipedBefore(@Param("deviceNumber") String deviceNumber, @Param("cutoff") long cutoff);

    @Update({"UPDATE devices SET hardwareid = #{hardwareId} WHERE number = #{deviceNumber}"})
    void updateHardwareId(@Param("deviceNumber") String deviceNumber,
                          @Param("hardwareId") String hardwareId);

    /**
     * Store the Android version into infojson (the device list reads
     * {@code devices.infojson ->> 'androidVersion'}; our agent reports it via telemetry/state).
     */
    @Update({"UPDATE devices SET infojson = jsonb_set(COALESCE(infojson, '{}'::jsonb), " +
            "'{androidVersion}', to_jsonb(#{androidVersion}::text), true) WHERE number = #{deviceNumber}"})
    void updateAndroidVersion(@Param("deviceNumber") String deviceNumber,
                              @Param("androidVersion") String androidVersion);

    // --- Location breadcrumb trail (device_location) ---

    /**
     * Append a fix ONLY when it's newer than everything stored — dedupe and insert in one
     * statement, so two concurrent check-ins carrying the same fix can't both slip past a
     * separate read-then-insert check.
     */
    @Insert({"INSERT INTO device_location (deviceNumber, lat, lon, accuracy, provider, capturedAt, recordedAt) " +
            "SELECT #{deviceNumber}, #{lat}, #{lon}, #{accuracy}, #{provider}, #{capturedAt}, #{recordedAt} " +
            "WHERE NOT EXISTS (SELECT 1 FROM device_location " +
            "WHERE deviceNumber = #{deviceNumber} AND capturedAt >= #{capturedAt})"})
    void insertLocation(DeviceLocation location);

    @Select({"SELECT * FROM device_location WHERE deviceNumber = #{deviceNumber} AND capturedAt >= #{since} " +
            "ORDER BY capturedAt DESC LIMIT #{limit}"})
    List<DeviceLocation> listLocations(@Param("deviceNumber") String deviceNumber,
                                       @Param("since") long since, @Param("limit") int limit);

    @Select({"SELECT number FROM devices WHERE configurationId = #{configurationId}"})
    List<String> listDeviceNumbersByConfigurationId(@Param("configurationId") int configurationId);

    /**
     * One row per device of the customer with its configuration + last applied revision (LEFT JOIN: never-reported
     * devices included), and whether its latest config.apply result has a deferred outcome (one lookup per device on
     * the agentCommand (deviceNumber, type, id) index). A wiped device stays in, marked: it can never report a new
     * revision, but it still blocks deleting its configuration (ConfigurationMapper.countDevices), so the summary counts
     * it apart.
     */
    @Select({"SELECT d.number AS deviceNumber, d.configurationId AS configurationId, d.agentCapabilities AS capabilitiesJson, " +
            "s.appliedConfigRevision AS appliedConfigRevision, d.wipedAt IS NOT NULL AS wiped, " +
            "COALESCE((SELECT c.detail LIKE '%\"deferred:%' FROM agentCommand c " +
            "          WHERE c.deviceNumber = d.number AND c.type = 'config.apply' ORDER BY c.id DESC LIMIT 1), false) " +
            "AS lastApplyDeferred " +
            "FROM devices d LEFT JOIN device_state s ON s.deviceNumber = d.number " +
            "WHERE d.customerId = #{customerId} AND d.configurationId IS NOT NULL"})
    List<DeviceSyncRow> listDevicesForSync(@Param("customerId") int customerId);
}
