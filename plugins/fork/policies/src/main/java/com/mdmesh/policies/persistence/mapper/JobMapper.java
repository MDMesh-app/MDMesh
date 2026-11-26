package com.mdmesh.policies.persistence.mapper;

import com.mdmesh.policies.persistence.domain.JobCommand;
import com.mdmesh.policies.persistence.domain.JobCount;
import com.mdmesh.policies.persistence.domain.JobPhone;
import com.mdmesh.policies.persistence.domain.JobRow;
import com.mdmesh.policies.persistence.domain.QueuedWake;
import com.mdmesh.policies.persistence.domain.RetryRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/** Bulk jobs: the plugin's own tables, and core's agentCommand rows the jobs queue. */
public interface JobMapper {

    // ---- creating a job ----

    /** Of these phone ids, the ones the user may access (as core decides it). */
    @Select({"<script>",
            "SELECT devices.id AS id, devices.number AS number, devices.configurationId AS configurationId,",
            "       devices.customerId AS customerId, s.agentVersion AS agentVersion, devices.wipedAt AS wipedAt",
            "FROM devices INNER JOIN users ON users.id = #{userId}",
            "LEFT JOIN device_state s ON s.deviceNumber = devices.number",
            "WHERE devices.id IN <foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach>",
            "AND " + FleetMapper.ACCESSIBLE,
            "</script>"})
    List<JobPhone> listAccessible(@Param("customerId") int customerId, @Param("userId") int userId,
                                  @Param("superAdmin") boolean superAdmin, @Param("ids") Collection<Integer> ids);

    @Insert({"INSERT INTO plugin_policies_job (customerId, createdBy, action, label, createdAt, deadline)",
            "VALUES (#{customerId}, #{createdBy}, #{action}, #{label}, #{createdAt}, #{deadline})"})
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertJob(JobRow job);

    @Insert({"<script>",
            "INSERT INTO plugin_policies_job_device (jobId, deviceNumber) VALUES",
            "<foreach item='n' collection='numbers' separator=','>(#{jobId}, #{n})</foreach>",
            "</script>"})
    void insertDevices(@Param("jobId") int jobId, @Param("numbers") List<String> numbers);

    /**
     * Queue commands in one statement: the rows go into core's agentCommand (the columns core's own insert fills),
     * and their ids are linked to the job in the same statement, so a command can't be queued without its link.
     */
    @Insert({"<script>",
            "WITH ins AS (",
            "  INSERT INTO agentCommand (deviceNumber, type, payload, requiresCapability, status, createdAt, completedAt, detail)",
            "  VALUES <foreach item='c' collection='commands' separator=','>",
            "    (#{c.deviceNumber}, #{c.type}, #{c.payload}, #{c.requiresCapability}, #{c.status}, #{c.createdAt},",
            "     #{c.completedAt}, #{c.detail})",
            "  </foreach>",
            "  RETURNING id, deviceNumber)",
            "INSERT INTO plugin_policies_job_command (jobId, deviceNumber, commandId)",
            "SELECT #{jobId}, ins.deviceNumber, ins.id FROM ins",
            "</script>"})
    void insertCommands(@Param("jobId") int jobId, @Param("commands") List<JobCommand> commands);

    // ---- the worker ----

    /** The next phones waiting for a wake, oldest first, across every job. */
    @Select({"SELECT id, jobId, deviceNumber FROM plugin_policies_job_device WHERE wake = 'queued' ORDER BY id LIMIT #{limit}"})
    List<QueuedWake> nextQueued(@Param("limit") int limit);

    @Update({"<script>",
            "UPDATE plugin_policies_job_device SET wake = #{wake}, wokenAt = #{at}",
            "WHERE wake = 'queued' AND id IN <foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach>",
            "</script>"})
    int markWake(@Param("ids") Collection<Integer> ids, @Param("wake") String wake, @Param("at") long at);

    /** Job commands that expired without ever reaching their phone, on jobs not cancelled, younger than {@code since}. */
    @Select({"SELECT jc.id AS linkId, jc.jobId AS jobId, jc.attempts AS attempts, c.deviceNumber AS deviceNumber,",
            "       c.type AS type, c.payload AS payload, c.requiresCapability AS requiresCapability",
            "FROM plugin_policies_job_command jc",
            "INNER JOIN plugin_policies_job j ON j.id = jc.jobId",
            "INNER JOIN agentCommand c ON c.id = jc.commandId",
            "WHERE c.status = 'expired' AND c.deliveredAt IS NULL AND NOT j.cancelled AND j.deadline IS NULL",
            "AND jc.attempts < #{maxAttempts} AND j.createdAt >= #{since}",
            "ORDER BY jc.id LIMIT #{limit}"})
    List<RetryRow> listRetries(@Param("maxAttempts") int maxAttempts, @Param("since") long since, @Param("limit") int limit);

    @Insert({"INSERT INTO agentCommand (deviceNumber, type, payload, requiresCapability, status, createdAt)",
            "VALUES (#{deviceNumber}, #{type}, #{payload}, #{requiresCapability}, #{status}, #{createdAt})"})
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertCommand(JobCommand command);

    /** Locks the job row for the rest of the transaction, so a retry and a cancel can't cross. */
    @Select({"SELECT cancelled FROM plugin_policies_job WHERE id = #{id} FOR UPDATE"})
    Boolean lockCancelled(@Param("id") int id);

    @Update({"UPDATE plugin_policies_job_command SET commandId = #{commandId}, attempts = attempts + 1 WHERE id = #{linkId}"})
    void relink(@Param("linkId") int linkId, @Param("commandId") int commandId);

    @Update({"UPDATE plugin_policies_job_device SET wake = 'queued', wokenAt = NULL",
            "WHERE jobId = #{jobId} AND deviceNumber = #{deviceNumber} AND wake <> 'queued' AND wake <> 'cancelled'"})
    void requeue(@Param("jobId") int jobId, @Param("deviceNumber") String deviceNumber);

    /** Expires scheduled commands still undelivered after their job's deadline: never sent late. */
    @Update({"UPDATE agentCommand SET status = 'expired', detail = 'Too late for its schedule: skipped', completedAt = #{now}",
            "WHERE id IN (SELECT jc.commandId FROM plugin_policies_job_command jc",
            "             INNER JOIN plugin_policies_job j ON j.id = jc.jobId",
            "             WHERE j.deadline IS NOT NULL AND j.deadline < #{now} AND j.createdAt > #{since})",
            "AND status = 'pending' AND deliveredAt IS NULL"})
    int expireLate(@Param("now") long now, @Param("since") long since);

    /** And stops waking their phones. */
    @Update({"UPDATE plugin_policies_job_device SET wake = 'cancelled' WHERE wake = 'queued'",
            "AND jobId IN (SELECT id FROM plugin_policies_job WHERE deadline IS NOT NULL AND deadline < #{now}",
            "              AND createdAt > #{since})"})
    int cancelLateWakes(@Param("now") long now, @Param("since") long since);

    @Delete({"DELETE FROM plugin_policies_job WHERE createdAt < #{before}"})
    int purge(@Param("before") long before);

    // ---- reading and cancelling ----

    @Select({"SELECT * FROM plugin_policies_job WHERE customerId = #{customerId} AND createdAt >= #{since}",
            "ORDER BY id DESC LIMIT #{limit}"})
    List<JobRow> listJobs(@Param("customerId") int customerId, @Param("since") long since, @Param("limit") int limit);

    @Select({"SELECT * FROM plugin_policies_job WHERE id = #{id} AND customerId = #{customerId}"})
    JobRow getJob(@Param("id") int id, @Param("customerId") int customerId);

    @Select({"<script>",
            "SELECT jobId, wake AS state, COUNT(*) AS count FROM plugin_policies_job_device",
            "WHERE jobId IN <foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach>",
            "GROUP BY jobId, wake",
            "</script>"})
    List<JobCount> countWakes(@Param("ids") Collection<Integer> ids);

    @Select({"<script>",
            "SELECT jc.jobId AS jobId, c.status AS state, COUNT(*) AS count",
            "FROM plugin_policies_job_command jc INNER JOIN agentCommand c ON c.id = jc.commandId",
            "WHERE jc.jobId IN <foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach>",
            "GROUP BY jc.jobId, c.status",
            "</script>"})
    List<JobCount> countCommands(@Param("ids") Collection<Integer> ids);

    @Update({"UPDATE plugin_policies_job SET cancelled = TRUE WHERE id = #{id} AND customerId = #{customerId} AND NOT cancelled"})
    int markCancelled(@Param("id") int id, @Param("customerId") int customerId);

    @Update({"UPDATE plugin_policies_job_device SET wake = 'cancelled' WHERE jobId = #{jobId} AND wake = 'queued'"})
    int cancelWakes(@Param("jobId") int jobId);

    /** Expire the job's commands no phone has received yet (core's own word for a command that won't run). */
    @Update({"UPDATE agentCommand SET status = 'expired', detail = 'Cancelled from the Policies page', completedAt = #{now}",
            "WHERE id IN (SELECT commandId FROM plugin_policies_job_command WHERE jobId = #{jobId})",
            "AND status = 'pending' AND deliveredAt IS NULL"})
    int expireUndelivered(@Param("jobId") int jobId, @Param("now") long now);
}
