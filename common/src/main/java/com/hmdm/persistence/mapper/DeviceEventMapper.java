package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.DeviceEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** MyBatis mapper for the agent lifecycle event stream ({@link DeviceEvent}). */
public interface DeviceEventMapper {

    @Insert({"INSERT INTO device_event (deviceNumber, type, ts, detail) " +
            "VALUES (#{deviceNumber}, #{type}, #{ts}, #{detail})"})
    void insert(DeviceEvent event);

    /** One multi-row INSERT, rows in list order (ids ascend with it). {@code events} must not be empty. */
    @Insert({"<script>INSERT INTO device_event (deviceNumber, type, ts, detail) VALUES "
            + "<foreach collection='events' item='e' separator=','>"
            + "(#{deviceNumber}, #{e.type}, #{e.ts}, #{e.detail})</foreach></script>"})
    void insertAll(@Param("deviceNumber") String deviceNumber, @Param("events") List<DeviceEvent> events);

    @Select({"SELECT id, deviceNumber, type, ts, detail FROM device_event " +
            "WHERE deviceNumber = #{deviceNumber} AND ts >= #{since} ORDER BY ts DESC, id DESC LIMIT #{limit}"})
    List<DeviceEvent> list(@Param("deviceNumber") String deviceNumber,
                           @Param("since") long since, @Param("limit") int limit);
}
