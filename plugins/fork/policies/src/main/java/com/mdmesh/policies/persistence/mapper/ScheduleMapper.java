package com.mdmesh.policies.persistence.mapper;

import com.mdmesh.policies.persistence.domain.AppSchedule;
import com.mdmesh.policies.persistence.domain.GroupLink;
import com.mdmesh.policies.persistence.domain.SchedulePhone;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** App schedules, and the phones and group data a firing needs. Scoped to one customer, except listEnabled. */
public interface ScheduleMapper {

    @Select({"SELECT * FROM plugin_policies_app_schedule WHERE customerId = #{customerId} AND configurationId = #{configurationId}",
            "ORDER BY id"})
    List<AppSchedule> list(@Param("customerId") int customerId, @Param("configurationId") int configurationId);

    @Select({"SELECT * FROM plugin_policies_app_schedule WHERE id = #{id} AND customerId = #{customerId}"})
    AppSchedule get(@Param("id") int id, @Param("customerId") int customerId);

    @Select({"SELECT * FROM plugin_policies_app_schedule WHERE enabled ORDER BY id"})
    List<AppSchedule> listEnabled();

    @Insert({"INSERT INTO plugin_policies_app_schedule (customerId, configurationId, pkg, appName, target, groupId, devices,",
            "days, times, timeZone, lateMinutes, enabled, createdAt)",
            "VALUES (#{customerId}, #{configurationId}, #{pkg}, #{appName}, #{target}, #{groupId}, #{devices},",
            "#{days}, #{times}, #{timeZone}, #{lateMinutes}, #{enabled}, #{createdAt})"})
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(AppSchedule s);

    /** The slot it last fired is kept, so an edited schedule never fires a past slot again. */
    @Update({"UPDATE plugin_policies_app_schedule SET pkg = #{pkg}, appName = #{appName}, target = #{target},",
            "groupId = #{groupId}, devices = #{devices}, days = #{days}, times = #{times}, timeZone = #{timeZone},",
            "lateMinutes = #{lateMinutes}, enabled = #{enabled}",
            "WHERE id = #{id} AND customerId = #{customerId}"})
    int update(AppSchedule s);

    @Delete({"DELETE FROM plugin_policies_app_schedule WHERE id = #{id} AND customerId = #{customerId}"})
    int delete(@Param("id") int id, @Param("customerId") int customerId);

    /** Claims a slot for firing: true for exactly one caller, once, even across restarts or two ticks. */
    @Update({"UPDATE plugin_policies_app_schedule SET lastSlot = #{slot}",
            "WHERE id = #{id} AND enabled AND (lastSlot IS NULL OR lastSlot < #{slot})"})
    int claimSlot(@Param("id") int id, @Param("slot") String slot);

    @Select({"SELECT COUNT(*) FROM configurations WHERE id = #{id} AND customerId = #{customerId}"})
    int countConfiguration(@Param("customerId") int customerId, @Param("id") int id);

    @Select({"SELECT name FROM plugin_policies_group WHERE id = #{id} AND customerId = #{customerId}"})
    String groupName(@Param("customerId") int customerId, @Param("id") int id);

    /** Every group's brands, to place each phone in its group as the console does. */
    @Select({"SELECT groupId, brand FROM plugin_policies_group_brand WHERE customerId = #{customerId}"})
    List<GroupLink> groupBrands(@Param("customerId") int customerId);

    /** The phones on a policy (not wiped), with their brand data, picked group and reported capabilities. */
    @Select({"SELECT d.id AS id, d.number AS number, d.description AS description, d.agentCapabilities AS capabilities,",
            "       s.telemetry AS telemetry,",
            "       db.brand AS setBrand, gd.groupId AS pickedGroupId",
            "FROM devices d",
            "LEFT JOIN device_state s ON s.deviceNumber = d.number",
            "LEFT JOIN plugin_policies_device_brand db ON db.deviceId = d.id AND db.customerId = d.customerId",
            "LEFT JOIN plugin_policies_group_device gd ON gd.deviceId = d.id AND gd.customerId = d.customerId",
            "WHERE d.customerId = #{customerId} AND d.configurationId = #{configurationId} AND d.wipedAt IS NULL",
            "ORDER BY d.id"})
    List<SchedulePhone> phones(@Param("customerId") int customerId, @Param("configurationId") int configurationId);
}
