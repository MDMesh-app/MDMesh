package com.mdmesh.policies.persistence.mapper;

import com.hmdm.persistence.mapper.DeviceMapper;
import com.mdmesh.policies.persistence.domain.FleetRow;
import com.mdmesh.policies.persistence.domain.OpenCommandRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Read-only queries over core's tables for the whole fleet at once. Both filter the phones exactly as core does for the
 * signed-in user: the customer's phones, and of those the ones the user may access (core's own
 * {@link DeviceMapper#USER_DEVICE_ACCESS} clause), unless the user is a super-admin.
 */
public interface FleetMapper {

    /** The phones the user may access, filtered as core filters them (needs {@code devices} and {@code users}). */
    String ACCESSIBLE = "devices.customerId = #{customerId} AND (#{superAdmin} OR " + DeviceMapper.USER_DEVICE_ACCESS + ")";

    /**
     * Every phone with its agent state, its latest config.apply and its latest finished app scan, in one query. The
     * two LATERAL lookups each read one row per phone from core's {@code (deviceNumber, type, id)} index, so the cost
     * grows with the number of phones, not with the length of their command histories.
     */
    @Select({"SELECT devices.id AS id, devices.number AS number, devices.configurationId AS configurationId,",
            "       devices.agentCapabilities AS capabilities,",
            "       s.telemetry AS telemetry, s.appliedConfigRevision AS appliedRevision, s.appliedConfigAt AS appliedAt,",
            "       s.agentVersion AS agentVersion,",
            "       ca.id AS applyId, ca.status AS applyStatus, ca.detail AS applyDetail,",
            "       ca.createdAt AS applyCreatedAt, ca.completedAt AS applyCompletedAt,",
            "       sc.detail AS scanDetail, sc.completedAt AS scannedAt",
            "FROM devices",
            "INNER JOIN users ON users.id = #{userId}",
            "LEFT JOIN device_state s ON s.deviceNumber = devices.number",
            "LEFT JOIN LATERAL (SELECT c.id, c.status, c.detail, c.createdAt, c.completedAt FROM agentCommand c",
            "                   WHERE c.deviceNumber = devices.number AND c.type = 'config.apply'",
            "                   ORDER BY c.id DESC LIMIT 1) ca ON TRUE",
            "LEFT JOIN LATERAL (SELECT c.detail, c.completedAt FROM agentCommand c",
            "                   WHERE c.deviceNumber = devices.number AND c.type = 'apps.scan' AND c.status = 'done'",
            "                   AND c.detail IS NOT NULL ORDER BY c.id DESC LIMIT 1) sc ON TRUE",
            "WHERE " + ACCESSIBLE,
            "ORDER BY devices.id"})
    List<FleetRow> listFleet(@Param("customerId") int customerId, @Param("userId") int userId,
                             @Param("superAdmin") boolean superAdmin);

    /** The open (queued, delivered or accepted) app scans and uninstalls of those phones. */
    @Select({"SELECT c.deviceNumber AS deviceNumber, c.type AS type, c.payload AS payload",
            "FROM agentCommand c",
            "INNER JOIN devices ON devices.number = c.deviceNumber",
            "INNER JOIN users ON users.id = #{userId}",
            "WHERE c.type IN ('apps.scan', 'app.uninstall') AND c.status IN ('pending', 'delivered', 'accepted')",
            "AND " + ACCESSIBLE})
    List<OpenCommandRow> listOpenCommands(@Param("customerId") int customerId, @Param("userId") int userId,
                                          @Param("superAdmin") boolean superAdmin);
}
