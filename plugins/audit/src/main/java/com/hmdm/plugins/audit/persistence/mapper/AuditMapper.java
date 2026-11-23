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

package com.hmdm.plugins.audit.persistence.mapper;

import com.hmdm.plugins.audit.persistence.domain.AuditLogRecord;
import com.hmdm.plugins.audit.persistence.domain.AuditScrubRow;
import com.hmdm.plugins.audit.rest.json.AuditLogFilter;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>An ORM mapper for {@link com.hmdm.plugins.audit.persistence.domain.AuditLogRecord} domain object.</p>
 *
 * @author isv
 */
public interface AuditMapper {

    @Insert({"INSERT INTO plugin_audit_log (" +
            "    createTime," +
            "    customerId," +
            "    userId," +
            "    login," +
            "    action," +
            "    payload," +
            "    ipAddress," +
            "    errorCode," +
            "    impersonatedBy" +
            ") " +
            "VALUES (" +
            "    #{createTime}," +
            "    #{customerId}," +
            "    #{userId}," +
            "    #{login}," +
            "    #{action}," +
            "    #{payload}," +
            "    #{ipAddress}," +
            "    #{errorCode}," +
            "    #{impersonatedBy}" +
            ")"})
    int insertAuditLogRecord(AuditLogRecord logRecord);

    List<AuditLogRecord> findAllLogRecordsByCustomerId(AuditLogFilter filter);

    long countAll(AuditLogFilter filter);

    /**
     * The scrub's progress: the last record scrubbed, its row locked until the transaction ends, so two runs never
     * scrub the same batch. Null when there is nothing to scrub (no state row).
     */
    @Select("SELECT lastId FROM plugin_audit_scrub WHERE id = 1 FOR UPDATE")
    Integer lockScrubProgress();

    /** The last record older releases wrote: the scrub stops there, a record written since is masked already. */
    @Select("SELECT upToId FROM plugin_audit_scrub WHERE id = 1")
    Integer scrubEnd();

    /**
     * The next records to scrub, oldest first, after {@code after} and up to {@code upTo}: at most {@code limit}, and
     * no more than {@code budget} bytes of payload in all (always at least one record). Sizes come from
     * {@code octet_length}, which reads a stored value's size without decompressing it, once per record, and only the
     * records kept are read. A payload over {@code big} bytes is not read: only its first 4096 and last 1024
     * characters (old releases stored bodies of any size, and a batch of them must not fill the server's memory).
     */
    @Select("WITH batch AS (" +
            "  SELECT id, COALESCE(octet_length(payload), 0) AS size FROM plugin_audit_log" +
            "  WHERE id > #{after} AND id <= #{upTo} ORDER BY id LIMIT #{limit}" +
            "), kept AS (" +
            "  SELECT id, size FROM (" +
            "    SELECT id, size, SUM(LEAST(size, #{big})) OVER (ORDER BY id) - LEAST(size, #{big}) AS usedBefore" +
            "    FROM batch) sized" +
            "  WHERE usedBefore < #{budget}" +
            ") " +
            "SELECT l.id, l.action," +
            "  CASE WHEN k.size > #{big} THEN NULL ELSE l.payload END AS payload," +
            "  CASE WHEN k.size > #{big} THEN left(l.payload, 4096) END AS head," +
            "  CASE WHEN k.size > #{big} THEN right(l.payload, 1024) END AS tail " +
            "FROM kept k JOIN plugin_audit_log l ON l.id = k.id ORDER BY l.id")
    List<AuditScrubRow> findForScrub(@Param("after") int after, @Param("upTo") int upTo, @Param("limit") int limit,
                                     @Param("big") int big, @Param("budget") long budget);

    @Update("UPDATE plugin_audit_log SET payload = #{payload} WHERE id = #{id}")
    void updatePayload(@Param("id") int id, @Param("payload") String payload);

    /**
     * Moves the scrub's end to the newest record, while it is not done: records a server still on the old release
     * wrote after the upgrade's changeset ran (a multi-node install updated one node at a time) get scrubbed too.
     */
    @Update("UPDATE plugin_audit_scrub SET upToId = GREATEST(upToId, " +
            "(SELECT COALESCE(MAX(id), 0) FROM plugin_audit_log)) WHERE id = 1 AND lastId < upToId")
    void extendScrubEnd();

    /** Bounds the scrub's transaction: a slow batch, or a wait for another node's, fails and is tried again later. */
    @Update("SET LOCAL statement_timeout = '120s'")
    void limitScrubStatements();

    @Update("SET LOCAL lock_timeout = '10s'")
    void limitScrubLockWait();

    @Update("UPDATE plugin_audit_scrub SET lastId = #{lastId} WHERE id = 1")
    void setScrubProgress(@Param("lastId") int lastId);
}
