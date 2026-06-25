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

    @Update({"UPDATE devices SET agentSecretHash = #{secretHash} WHERE number = #{deviceNumber}"})
    void updateDeviceSecretHash(@Param("deviceNumber") String deviceNumber,
                                @Param("secretHash") String secretHash);

    @Select({"SELECT agentSecretHash FROM devices WHERE number = #{deviceNumber}"})
    String getDeviceSecretHash(@Param("deviceNumber") String deviceNumber);

    @Update({"UPDATE devices SET lastUpdate = #{ts} WHERE number = #{deviceNumber}"})
    void updateLastUpdate(@Param("deviceNumber") String deviceNumber, @Param("ts") long ts);

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

    @Insert({"INSERT INTO device_location (deviceNumber, lat, lon, accuracy, provider, capturedAt, recordedAt) " +
            "VALUES (#{deviceNumber}, #{lat}, #{lon}, #{accuracy}, #{provider}, #{capturedAt}, #{recordedAt})"})
    void insertLocation(DeviceLocation location);

    /** Newest stored fix's capturedAt for a device, or null if none — used to de-dupe repeats. */
    @Select({"SELECT MAX(capturedAt) FROM device_location WHERE deviceNumber = #{deviceNumber}"})
    Long getLastCapturedAt(@Param("deviceNumber") String deviceNumber);

    @Select({"SELECT * FROM device_location WHERE deviceNumber = #{deviceNumber} AND capturedAt >= #{since} " +
            "ORDER BY capturedAt DESC LIMIT #{limit}"})
    List<DeviceLocation> listLocations(@Param("deviceNumber") String deviceNumber,
                                       @Param("since") long since, @Param("limit") int limit);
}
