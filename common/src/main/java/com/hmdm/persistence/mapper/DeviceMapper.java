/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 * Modified by the MDMesh contributors.
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

import java.util.List;

import com.hmdm.persistence.domain.*;
import com.hmdm.service.DeviceApplicationsStatus;
import com.hmdm.service.DeviceConfigFilesStatus;
import org.apache.ibatis.annotations.*;
import com.hmdm.rest.json.DeviceLookupItem;

public interface DeviceMapper {

    int insertDevice(Device device);

    void insertDeviceGroups(@Param("id") Integer deviceId,
                            @Param("groups") List<Integer> groups);

    /** Takes the device out of every group the user may use ({@link #USABLE_GROUPS}); the others stay. */
    @Delete({"DELETE FROM deviceGroups WHERE deviceId = #{deviceId} AND groupId IN (" + USABLE_GROUPS + ")"})
    void removeDeviceGroupsByDeviceId(
            @Param("userId") int userId,
            @Param("customerId") int customerId,
            @Param("deviceId") Integer deviceId);

    void updateDevice(Device device);

    /** The device-group rule of the device list: all devices of the customer, or those in the user's groups. */
    String USER_DEVICE_ACCESS =
            "(users.allDevicesAvailable = TRUE " +
            " OR EXISTS (SELECT 1 FROM deviceGroups " +
            "            INNER JOIN userDeviceGroupsAccess access ON access.groupId = deviceGroups.groupId " +
            "            WHERE deviceGroups.deviceId = devices.id AND access.userId = users.id))";

    @Select({"SELECT COUNT(*) > 0 FROM devices " +
            "INNER JOIN users ON users.id = #{userId} " +
            "WHERE devices.id = #{deviceId} AND devices.customerId = #{customerId} " +
            "AND " + USER_DEVICE_ACCESS})
    boolean isDeviceAccessibleToUser(@Param("deviceId") int deviceId,
                                     @Param("userId") int userId,
                                     @Param("customerId") int customerId);

    @Select({"SELECT devices.number FROM devices " +
            "INNER JOIN users ON users.id = #{userId} " +
            "WHERE devices.customerId = #{customerId} " +
            "AND " + USER_DEVICE_ACCESS})
    List<String> getDeviceNumbersAccessibleToUser(@Param("userId") int userId,
                                                  @Param("customerId") int customerId);

    Device getDeviceByNumber(@Param("number") String number);

    Device getDeviceByOldNumber(@Param("number") String number);

    Device getDeviceByNumberIgnoreCase(@Param("number") String number);

    Device getDeviceByImeiOrSerial(@Param("number") String number);

    Device getDeviceById(@Param("id") Integer id);

    @Select({"SELECT * FROM devices " +
            "WHERE configurationId = #{configurationId} AND customerId = #{customerId}"})
    List<Device> getAllConfigurationDevices(@Param("configurationId") int configurationId,
                                            @Param("customerId") int customerId);

    @Select({"SELECT * FROM devices " +
            "LEFT JOIN deviceGroups ON deviceGroups.deviceId = devices.id " +
            "WHERE deviceGroups.groupId = #{groupId} AND devices.customerId = #{customerId}"})
    List<Device> getAllGroupDevices(@Param("groupId") int groupId, @Param("customerId") int customerId);

    @Select({"SELECT * FROM devices " +
            "WHERE customerId = #{customerId}"})
    List<Device> getAllCustomerDevices(@Param("customerId") int customerId);

    List<Device> getAllDevices(DeviceSearchRequest deviceSearchRequest);

    @Select({"SELECT COUNT(*) " +
            "FROM devices " +
            "WHERE customerId = #{customerId}"})
    Long countAllDevicesForCustomer(@Param("customerId") Integer customerId);

    /** Serialises device counting for the customer until the transaction ends (see CustomerDeviceLimit). */
    @Select({com.hmdm.persistence.CustomerDeviceLimit.LOCK_SQL})
    Integer lockCustomerDevices(@Param("customerId") int customerId);

    @Select({"SELECT COUNT(*) " +
            "FROM devices"})
    Long countTotalDevices();

    @Select({"SELECT COUNT(*) " +
            "FROM devices " +
            "WHERE devices.lastUpdate >= extract(epoch from now()) * 1000 - 3600000"})
    Long countOnlineDevices();

    Long countAllDevices(DeviceSearchRequest filter);

    Long countAllDevicesForSummary(DeviceSummaryRequest filter);

    List<SummaryConfigItem> countDevicesByConfig(DeviceSummaryRequest filter);

    @Update({"UPDATE devices SET " +
            "  info = #{info}, " +
            "  infojson = #{info}::json, " +
            "  lastUpdate = CAST(EXTRACT(EPOCH FROM NOW()) * 1000 AS BIGINT), " +
            "  enrollTime = COALESCE(enrollTime, CAST(EXTRACT(EPOCH FROM NOW()) * 1000 AS BIGINT)), " +
            "  imeiUpdateTs = #{imeiUpdateTs}, " +
            "  publicIp = #{publicIp} " +
            "WHERE id = #{deviceId}"})
    void updateDeviceInfo(@Param("deviceId") Integer deviceId,
                          @Param("info") String info,
                          @Param("imeiUpdateTs") Long imeiUpdateTs,
                          @Param("publicIp") String publicIp);

    @Update({"UPDATE devices SET " +
            "  custom1 = #{custom1}, " +
            "  custom2 = #{custom2}, " +
            "  custom3 = #{custom3} " +
            "WHERE id = #{deviceId}"})
    void updateDeviceCustomProperties(@Param("deviceId") Integer deviceId,
                                      @Param("custom1") String custom1,
                                      @Param("custom2") String custom2,
                                      @Param("custom3") String custom3);

    @Update({"UPDATE devices SET oldNumber = null " +
            "WHERE id = #{deviceId}"})
    void clearOldNumber(@Param("deviceId") Integer deviceId);

    List<DeviceLookupItem> lookupDevices(@Param("userId") int userId,
                                         @Param("customerId") int customerId,
                                         @Param("filter") String filter,
                                         @Param("limit") int limit);

    /** An Integer[] bound as one PostgreSQL array: one parameter however many ids (no bind-parameter limit). */
    String IDS = "#{ids,typeHandler=org.apache.ibatis.type.ArrayTypeHandler}";

    /** Locks the devices' rows, in id order. @return the ids that exist, in that order */
    @Select({"SELECT id FROM devices WHERE id = ANY(" + IDS + ") ORDER BY id FOR UPDATE"})
    List<Integer> lockDevices(@Param("ids") Integer[] ids);

    /** Locks the devices' rows, in id order. @return those that exist (id, number, customer, configuration) */
    @Select({"SELECT id, number, customerId, configurationId FROM devices WHERE id = ANY(" + IDS + ") " +
            "ORDER BY id FOR UPDATE"})
    List<Device> lockDevicesForUpdate(@Param("ids") Integer[] ids);

    /** The ids among {@code ids} the user may not reach (the rule of {@code DeviceDAO.isAccessibleToUser}), in order. */
    @Select({"SELECT devices.id FROM devices LEFT JOIN users ON users.id = #{userId} " +
            "WHERE devices.id = ANY(" + IDS + ") " +
            "AND NOT COALESCE(devices.customerId = #{customerId} AND (#{superAdmin} OR " + USER_DEVICE_ACCESS + "), " +
            "FALSE) ORDER BY devices.id"})
    List<Integer> inaccessibleDevices(@Param("ids") Integer[] ids, @Param("userId") int userId,
                                      @Param("customerId") int customerId, @Param("superAdmin") boolean superAdmin);

    /**
     * Moves the devices to the configuration, in one statement; only into a configuration of the device's own customer.
     * @return the ids of the devices moved. A select that writes: affectData marks the session dirty, so the
     *         transaction commits it (else only the connection's reset on close would, swallowing any error).
     */
    @Select(affectData = true, value = {"UPDATE devices SET configurationId = #{configurationId} WHERE id = ANY(" + IDS + ") " +
            "AND EXISTS (SELECT 1 FROM configurations " +
            "            WHERE configurations.id = #{configurationId} AND configurations.customerId = devices.customerId) " +
            "RETURNING id"})
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<Integer> updateDevicesConfiguration(@Param("ids") Integer[] ids, @Param("configurationId") Integer configurationId);

    /**
     * The groups the user may put devices in (or take them out of): groups of the customer the user belongs to, every
     * one of them for a user with all devices available, else those userDeviceGroupsAccess grants. Same rule as
     * getAllGroups, the groups the console offers. The one rule for every device-group change in DeviceDAO.
     */
    String USABLE_GROUPS =
            "SELECT groups.id FROM groups INNER JOIN users ON users.id = #{userId} " +
            "WHERE groups.customerId = #{customerId} AND users.customerId = #{customerId} " +
            "AND (users.allDevicesAvailable = TRUE " +
            "     OR EXISTS (SELECT 1 FROM userDeviceGroupsAccess access " +
            "                WHERE access.groupId = groups.id AND access.userId = users.id))";

    /** The ids among {@code groupIds} that aren't {@link #USABLE_GROUPS}, in order. */
    @Select({"SELECT g.id FROM unnest(#{groupIds,typeHandler=org.apache.ibatis.type.ArrayTypeHandler}) AS g(id) " +
            "WHERE g.id NOT IN (" + USABLE_GROUPS + ") ORDER BY g.id"})
    List<Integer> unusableGroups(@Param("userId") int userId, @Param("customerId") int customerId,
                                 @Param("groupIds") Integer[] groupIds);

    /** Puts every device in every group (those it isn't in yet), in one statement. */
    @Insert({"INSERT INTO deviceGroups (deviceId, groupId) " +
            "SELECT d.id, g.id FROM unnest(" + IDS + ") AS d(id) " +
            "CROSS JOIN unnest(#{groupIds,typeHandler=org.apache.ibatis.type.ArrayTypeHandler}) AS g(id) " +
            "WHERE g.id IN (" + USABLE_GROUPS + ") " +
            "AND NOT EXISTS (SELECT 1 FROM deviceGroups x WHERE x.deviceId = d.id AND x.groupId = g.id)"})
    int addDevicesToGroups(@Param("userId") int userId, @Param("customerId") int customerId,
                           @Param("ids") Integer[] ids, @Param("groupIds") Integer[] groupIds);

    /** Takes every device out of every group, in one statement. */
    @Delete({"DELETE FROM deviceGroups WHERE deviceId = ANY(" + IDS + ") " +
            "AND groupId = ANY(#{groupIds,typeHandler=org.apache.ibatis.type.ArrayTypeHandler}) " +
            "AND groupId IN (" + USABLE_GROUPS + ")"})
    int removeDevicesFromGroups(@Param("userId") int userId, @Param("customerId") int customerId,
                                @Param("ids") Integer[] ids, @Param("groupIds") Integer[] groupIds);

    /** Locks the device's row (any device), then reads its number when it is enrolled with the agent (it has an agent
     *  secret), else null. */
    @Select({"SELECT CASE WHEN agentSecretHash IS NULL THEN NULL ELSE number END FROM devices WHERE id = #{id} " +
            "FOR UPDATE"})
    String lockAgentDeviceNumber(@Param("id") Integer id);

    /** Its agent history (commands, state, events, locations, rollout canary membership) goes with it: those tables
     *  reference devices(number) ON DELETE CASCADE, as the rows keyed by id do devices(id). */
    @Delete({"DELETE FROM devices WHERE id = #{id}"})
    void removeDevice(@Param("id") Integer id);

    @Select({"SELECT COUNT(*) > 0 FROM configurations WHERE id = #{configurationId} AND customerId = #{customerId}"})
    boolean isCustomerConfiguration(@Param("configurationId") int configurationId,
                                    @Param("customerId") int customerId);

    @Select({"SELECT COUNT(*) > 0 FROM groups WHERE id = #{groupId} AND customerId = #{customerId}"})
    boolean isCustomerGroup(@Param("groupId") int groupId, @Param("customerId") int customerId);

    /**
     * Whether the MDMesh agent enrolled the device: it has a check-in secret (AgentEnrollmentDAO creates the device
     * and gives it its secret in one transaction, so a device row never changes between the two kinds).
     */
    @Select({"SELECT EXISTS (SELECT 1 FROM devices WHERE id = #{id} AND agentSecretHash IS NOT NULL)"})
    boolean isAgentDevice(@Param("id") int id);

    /**
     * Whether the configuration is agent-managed: an agent-enrolled device ({@link #isAgentDevice}) uses it, or an
     * enrollment token live at {@code now} would enroll a device into it (the token's own configuration or, for a
     * token without one, its customer's default configuration for new devices).
     */
    @Select({"SELECT EXISTS (SELECT 1 FROM devices " +
            "WHERE configurationId = #{configurationId} AND agentSecretHash IS NOT NULL) " +
            "OR EXISTS (SELECT 1 FROM agentEnrollmentToken WHERE " + AgentEnrollmentTokenMapper.LIVE + " " +
            "AND (configurationId = #{configurationId} OR (configurationId IS NULL AND customerId IN " +
            "(SELECT customerId FROM settings WHERE newDeviceConfigurationId = #{configurationId}))))"})
    boolean isAgentConfiguration(@Param("configurationId") int configurationId, @Param("now") long now);

    @Update({"UPDATE devices SET description = #{description} WHERE id = #{deviceId}"})
    void updateDeviceDescription(@Param("deviceId") Integer deviceId,
                                 @Param("description") String newDeviceDesc);

    @Update({"UPDATE devices SET fastSearch = RIGHT(number, #{fastSearchChars}) WHERE fastSearch IS NULL " +
            " OR LENGTH(fastSearch) != #{fastSearchChars}"})
    void updateFastSearch(@Param("fastSearchChars") Integer fastSearchChars);

    List<Group> getAllGroups(@Param("customerId") int customerId,
                             @Param("userId") Integer userId);

    List<Group> getAllGroupsByValue(@Param("customerId") int customerId,
                                    @Param("value") String value,
                                    @Param("userId") Integer userId);

    @Select({"SELECT * FROM groups WHERE customerId=#{customerId} AND name = #{name}"})
    Group getGroupByName(@Param("customerId") int customerId, @Param("name") String name);

    @Insert({"INSERT INTO groups (name, customerId) VALUES (#{name}, #{customerId})"})
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertGroup(Group group);

    @Update({"UPDATE groups SET name = #{name} WHERE id = #{id}"})
    void updateGroup(Group group);

    @Select({"SELECT COUNT(*) " +
            "FROM deviceGroups " +
            "WHERE groupId = #{groupId}"})
    Long countDevicesByGroupId(@Param("groupId") Integer groupId);

    @Delete({"DELETE FROM groups WHERE id = #{id}"})
    void removeGroupById(@Param("id") Integer id);

    @Select({"SELECT * FROM groups WHERE id = #{id}"})
    Group getGroupById(@Param("id") Integer id);

    @Select("SELECT devices.id FROM devices WHERE customerId = #{customerId} AND configurationId = #{configurationId}")
    List<Device> getDeviceIdsByConfigurationId(@Param("customerId") Integer customerId,
                                               @Param("configurationId") int configurationId);

    @Select("SELECT devices.id FROM devices WHERE configurationId = #{configurationId}")
    List<Device> getDeviceIdsBySoleConfigurationId(@Param("configurationId") int configurationId);

    void insertDeviceApplicationSettings(@Param("id") Integer deviceId,
                                         @Param("appSettings") List<ApplicationSetting> applicationSettings);

    @Delete("DELETE FROM deviceApplicationSettings WHERE extRefId = #{id}")
    void deleteDeviceApplicationSettings(@Param("id") Integer deviceId);

    @Select("SELECT " +
            "    deviceApps.app ->> 'pkg' AS pkg, " +
            "    deviceApps.app ->> 'version' AS version, " +
            "    deviceApps.app ->> 'name' AS name " +
            "FROM (" +
            "    SELECT jsonb_array_elements(infojson -> 'applications') AS app " +
            "    FROM devices " +
            "    WHERE id = #{deviceId}" +
            ") deviceApps")
    List<DeviceApplication> getDeviceInstalledApplications(@Param("deviceId") int deviceId);

    @Update("INSERT INTO deviceStatuses (deviceId, configFilesStatus, applicationsStatus) " +
            "VALUES (#{deviceId}, #{filesStatus}, #{appsStatus})" +
            "ON CONFLICT ON CONSTRAINT deviceStatuses_pr_key DO " +
            "UPDATE SET configFilesStatus = EXCLUDED.configFilesStatus, applicationsStatus = EXCLUDED.applicationsStatus")
    int updateDeviceStatuses(@Param("deviceId") Integer deviceId,
                             @Param("filesStatus") DeviceConfigFilesStatus deviceConfigFilesStatus,
                             @Param("appsStatus") DeviceApplicationsStatus deviceApplicatiosStatus);

    @Select("SELECT id FROM devices")
    List<Integer> getAllDeviceIds();
}
