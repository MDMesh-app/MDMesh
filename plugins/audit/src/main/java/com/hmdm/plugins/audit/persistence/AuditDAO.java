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

package com.hmdm.plugins.audit.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.AbstractDAO;
import com.hmdm.plugins.audit.persistence.domain.AuditLogRecord;
import com.hmdm.plugins.audit.persistence.domain.AuditScrubRow;
import com.hmdm.plugins.audit.persistence.mapper.AuditMapper;
import com.hmdm.plugins.audit.rest.filter.StoredPayloads;
import com.hmdm.plugins.audit.rest.json.AuditLogFilter;
import com.hmdm.security.SecurityContext;
import org.mybatis.guice.transactional.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <p>A DAO for {@link AuditLogRecord} domain objects.</p>
 *
 * @author isv
 */
@Singleton
public class AuditDAO extends AbstractDAO<AuditLogRecord> {

    /**
     * <p>An ORM mapper used for managing the audit log records data the database.</p>
     */
    private final AuditMapper mapper;

    /**
     * <p>Constructs new <code>AuditDAO</code> instance. This implementation does nothing.</p>
     */
    @Inject
    public AuditDAO(AuditMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * <p>Inserts an audit log record into database.</p>
     *
     * @param logRecord a log record to be inserted.
     */
    public void insertAuditLogRecord(AuditLogRecord logRecord) {
        this.mapper.insertAuditLogRecord(logRecord);
    }

    /**
     * <p>Finds the audit log records matching the specified filter.</p>
     *
     * @param filter a filter used to narrowing down the search results.
     * @return a list of audit log records matching the specified filter.
     */
    @Transactional
    public List<AuditLogRecord> findAll(AuditLogFilter filter) {
        prepareFilter(filter);

        final List<AuditLogRecord> result = this.getListWithCurrentUser(currentUser -> {
            filter.setCustomerId(currentUser.getCustomerId());
            filter.setUserId(currentUser.getId());
            return this.mapper.findAllLogRecordsByCustomerId(filter);
        });

        return new ArrayList<>(result);
    }

    /**
     * <p>Counts the audit log records matching the specified filter.</p>
     *
     * @param filter a filter used to narrowing down the search results.
     * @return a number of audit log records matching the specified filter.
     */
    public long countAll(AuditLogFilter filter) {
        prepareFilter(filter);
        return SecurityContext.get().getCurrentUser()
                .map(user -> {
                    filter.setCustomerId(user.getCustomerId());
                    return this.mapper.countAll(filter);
                })
                .orElse(0L);
    }

    /** Moves the scrub's end to the newest record while the scrub is not done ({@link AuditMapper#extendScrubEnd}). */
    public void extendScrubEnd() {
        this.mapper.extendScrubEnd();
    }

    /**
     * <p>Scrubs the next {@code limit} records older releases stored ({@link StoredPayloads#scrub}: the one scrub
     * policy), in one transaction, and moves the progress past them. Records are taken in id order from the stored
     * progress, so an interrupted scrub goes on where it stopped, and a record is rewritten only when its payload
     * changes.</p>
     *
     * @param limit the most records to scrub.
     * @return how many records it rewrote, or -1 when the scrub is done.
     */
    @Transactional
    public int scrubOldRecords(int limit) {
        this.mapper.limitScrubStatements();
        this.mapper.limitScrubLockWait();
        Integer last = this.mapper.lockScrubProgress();
        Integer upTo = this.mapper.scrubEnd();
        if (last == null || upTo == null || last >= upTo) {
            return -1;
        }
        List<AuditScrubRow> batch = this.mapper.findForScrub(last, upTo, limit, StoredPayloads.READ_WHOLE,
                StoredPayloads.BATCH_BYTES);
        int rewritten = 0;
        for (AuditScrubRow row : batch) {
            String scrubbed = row.getPayload() != null || row.getHead() == null
                    ? StoredPayloads.scrub(row.getAction(), row.getPayload())
                    : StoredPayloads.scrubTooLarge(row.getHead(), row.getTail());
            if (scrubbed != null && !Objects.equals(scrubbed, row.getPayload())) {
                this.mapper.updatePayload(row.getId(), scrubbed);
                rewritten++;
            }
        }
        // None left: done (ids may have gaps up to upTo)
        int next = batch.isEmpty() ? upTo : batch.get(batch.size() - 1).getId();
        this.mapper.setScrubProgress(next);
        return rewritten;
    }

    /**
     * <p>Prepares the filter for usage by mapper.</p>
     *
     * @param filter a filter provided by request.
     */
    private static void prepareFilter(AuditLogFilter filter) {
        if (filter.getMessageFilter() != null) {
            if (filter.getMessageFilter().trim().isEmpty()) {
                filter.setMessageFilter(null);
            } else {
                filter.setMessageFilter('%' + filter.getMessageFilter().trim() + '%');
            }
        }
        if (filter.getUserFilter() != null) {
            if (filter.getUserFilter().trim().isEmpty()) {
                filter.setUserFilter(null);
            } else {
                filter.setUserFilter(filter.getUserFilter().trim());
            }
        }
    }
}
