package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.DeviceState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis mapper for the latest agent-reported device snapshot ({@link DeviceState}).
 * One row per device number; {@link #upsert} replaces the prior snapshot.
 */
public interface DeviceStateMapper {

    @Insert({"INSERT INTO device_state " +
            "(deviceNumber, battery, charging, locked, kioskActive, androidRelease, lastBootAt, updatedAt, agentVersion, powerMode, telemetry, appliedConfigRevision, appliedConfigAt) " +
            "VALUES (#{deviceNumber}, #{battery}, #{charging}, #{locked}, #{kioskActive}, " +
            "#{androidRelease}, #{lastBootAt}, #{updatedAt}, #{agentVersion}, #{powerMode}, #{telemetry}, #{appliedConfigRevision}, #{appliedConfigAt}) " +
            "ON CONFLICT (deviceNumber) DO UPDATE SET " +
            "battery = EXCLUDED.battery, charging = EXCLUDED.charging, locked = EXCLUDED.locked, " +
            "kioskActive = EXCLUDED.kioskActive, androidRelease = EXCLUDED.androidRelease, " +
            "lastBootAt = EXCLUDED.lastBootAt, updatedAt = EXCLUDED.updatedAt, " +
            "agentVersion = EXCLUDED.agentVersion, powerMode = EXCLUDED.powerMode, telemetry = EXCLUDED.telemetry, " +
            // Keep the last known applied revision when a check-in omits it (state without config info).
            "appliedConfigRevision = COALESCE(EXCLUDED.appliedConfigRevision, device_state.appliedConfigRevision), " +
            "appliedConfigAt = CASE WHEN EXCLUDED.appliedConfigRevision IS NULL THEN device_state.appliedConfigAt " +
            "                       WHEN EXCLUDED.appliedConfigRevision IS DISTINCT FROM device_state.appliedConfigRevision THEN EXCLUDED.updatedAt " +
            "                       ELSE COALESCE(device_state.appliedConfigAt, EXCLUDED.updatedAt) END"})
    void upsert(DeviceState state);

    @Select({"SELECT deviceNumber, battery, charging, locked, kioskActive, androidRelease, lastBootAt, updatedAt, agentVersion, powerMode, telemetry, appliedConfigRevision, appliedConfigAt " +
            "FROM device_state WHERE deviceNumber = #{deviceNumber}"})
    DeviceState findByDeviceNumber(@Param("deviceNumber") String deviceNumber);
}
