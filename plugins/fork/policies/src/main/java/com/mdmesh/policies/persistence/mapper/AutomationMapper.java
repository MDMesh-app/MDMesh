package com.mdmesh.policies.persistence.mapper;

import com.mdmesh.policies.persistence.domain.AutomationSettings;
import com.mdmesh.policies.persistence.domain.FreshScan;
import com.mdmesh.policies.persistence.domain.JobPhone;
import com.mdmesh.policies.persistence.domain.KeyValue;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * Automation: the settings table, and read-only queries over core's tables for the nightly sweep and auto-remove.
 * Wiped phones are always left out.
 */
public interface AutomationMapper {

    // ---- settings ----

    @Select({"SELECT * FROM plugin_policies_settings WHERE customerId = #{customerId}"})
    AutomationSettings getSettings(@Param("customerId") int customerId);

    @Select({"SELECT * FROM plugin_policies_settings WHERE nightlyScan"})
    List<AutomationSettings> listScanning();

    /** Creates the customer's row, or changes only its settings (the sweep's state is kept). */
    @Insert({"INSERT INTO plugin_policies_settings (customerId, nightlyScan, windowStart, windowEnd, timeZone)",
            "VALUES (#{customerId}, #{nightlyScan}, #{windowStart}, #{windowEnd}, #{timeZone})",
            "ON CONFLICT (customerId) DO UPDATE SET nightlyScan = EXCLUDED.nightlyScan,",
            "windowStart = EXCLUDED.windowStart, windowEnd = EXCLUDED.windowEnd, timeZone = EXCLUDED.timeZone"})
    void saveSettings(AutomationSettings s);

    @Update({"UPDATE plugin_policies_settings SET sweepNight = #{night}, sweepCursor = #{cursor}, sweepJobId = #{jobId}",
            "WHERE customerId = #{customerId}"})
    void saveSweep(@Param("customerId") int customerId, @Param("night") String night, @Param("cursor") int cursor,
                   @Param("jobId") Integer jobId);

    /** Records the auto-remove check time, creating the customer's row (nightly scans off) if it has none. */
    @Insert({"INSERT INTO plugin_policies_settings (customerId, autoRemoveSince) VALUES (#{customerId}, #{since})",
            "ON CONFLICT (customerId) DO UPDATE SET autoRemoveSince = EXCLUDED.autoRemoveSince"})
    void saveAutoRemoveSince(@Param("customerId") int customerId, @Param("since") long since);

    // ---- the nightly sweep ----

    @Select({"SELECT COUNT(*) FROM devices WHERE customerId = #{customerId} AND wipedAt IS NULL"})
    int countPhones(@Param("customerId") int customerId);

    @Select({"SELECT COUNT(*) FROM devices WHERE customerId = #{customerId} AND wipedAt IS NULL AND id <= #{cursor}"})
    int countPhonesUpTo(@Param("customerId") int customerId, @Param("cursor") int cursor);

    /**
     * The next phones of tonight's sweep: ids above the cursor, in id order (the sweep's direction), leaving out phones
     * that got an app scan since {@code recentSince} (scanned already, or a scan on its way).
     */
    @Select({"SELECT d.id AS id, d.number AS number, d.configurationId AS configurationId, d.customerId AS customerId",
            "FROM devices d",
            "WHERE d.customerId = #{customerId} AND d.wipedAt IS NULL AND d.id > #{cursor}",
            "AND NOT EXISTS (SELECT 1 FROM agentCommand c WHERE c.deviceNumber = d.number AND c.type = 'apps.scan'",
            "                AND c.createdAt > #{recentSince})",
            "ORDER BY d.id LIMIT #{limit}"})
    List<JobPhone> sweepBatch(@Param("customerId") int customerId, @Param("cursor") int cursor,
                              @Param("recentSince") long recentSince, @Param("limit") int limit);

    /** Phones never scanned, with no scan sent to them since {@code recentSince}: new phones, scanned first. */
    @Select({"SELECT d.id AS id, d.number AS number, d.configurationId AS configurationId, d.customerId AS customerId",
            "FROM devices d",
            "WHERE d.customerId = #{customerId} AND d.wipedAt IS NULL",
            "AND NOT EXISTS (SELECT 1 FROM agentCommand c WHERE c.deviceNumber = d.number AND c.type = 'apps.scan'",
            "                AND (c.status = 'done' OR c.createdAt > #{recentSince}))",
            "ORDER BY d.id LIMIT #{limit}"})
    List<JobPhone> neverScanned(@Param("customerId") int customerId, @Param("recentSince") long recentSince,
                                @Param("limit") int limit);

    // ---- auto-remove ----

    /** Customers with a configuration in kiosk whose policy is set to auto-remove. */
    @Select({"SELECT DISTINCT customerId FROM configurations",
            "WHERE kioskMode AND extensions -> 'policies' -> 'apps' ->> 'mode' = 'auto'"})
    List<Integer> listAutoRemoveCustomers();

    /**
     * Phones on such a configuration whose latest finished scan completed in ({@code since}, {@code until}]. Only a
     * scan made after auto-remove was checking is ever acted on.
     */
    @Select({"SELECT d.id AS id, d.number AS number, d.configurationId AS configurationId, d.customerId AS customerId,",
            "       sc.detail AS scanDetail, sc.completedAt AS scannedAt",
            "FROM devices d",
            "INNER JOIN configurations cfg ON cfg.id = d.configurationId",
            "INNER JOIN LATERAL (SELECT c.detail, c.completedAt FROM agentCommand c",
            "                    WHERE c.deviceNumber = d.number AND c.type = 'apps.scan' AND c.status = 'done'",
            "                    AND c.detail IS NOT NULL ORDER BY c.id DESC LIMIT 1) sc ON TRUE",
            "WHERE d.customerId = #{customerId} AND d.wipedAt IS NULL",
            "AND cfg.kioskMode AND cfg.extensions -> 'policies' -> 'apps' ->> 'mode' = 'auto'",
            "AND sc.completedAt > #{since} AND sc.completedAt <= #{until}",
            "ORDER BY d.id"})
    List<FreshScan> freshScans(@Param("customerId") int customerId, @Param("since") long since, @Param("until") long until);

    /** Each configuration's policy data (its extensions.policies namespace) as JSON text. */
    @Select({"<script>",
            "SELECT CAST(id AS VARCHAR) AS key, (extensions -> 'policies')::text AS value FROM configurations",
            "WHERE id IN <foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach>",
            "</script>"})
    List<KeyValue> policyData(@Param("ids") Collection<Integer> ids);

    /** The packages each configuration allows on the kiosk screen (Install and shown in kiosk: the server's kiosk rule). */
    @Select({"<script>",
            "SELECT CAST(ca.configurationId AS VARCHAR) AS key, a.pkg AS value",
            "FROM configurationApplications ca INNER JOIN applications a ON a.id = ca.applicationId",
            "WHERE ca.action = 1 AND ca.showIcon AND a.pkg IS NOT NULL",
            "AND ca.configurationId IN <foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach>",
            "</script>"})
    List<KeyValue> allowedPackages(@Param("ids") Collection<Integer> ids);

    /** Open uninstalls of these phones (phone number, payload). */
    @Select({"<script>",
            "SELECT deviceNumber AS key, payload AS value FROM agentCommand",
            "WHERE type = 'app.uninstall' AND status IN ('pending', 'delivered', 'accepted')",
            "AND deviceNumber IN <foreach item='n' collection='numbers' open='(' separator=',' close=')'>#{n}</foreach>",
            "</script>"})
    List<KeyValue> openUninstalls(@Param("numbers") Collection<String> numbers);
}
