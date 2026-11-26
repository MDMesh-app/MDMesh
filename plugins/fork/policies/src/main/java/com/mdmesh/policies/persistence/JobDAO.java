package com.mdmesh.policies.persistence;

import com.mdmesh.policies.persistence.domain.JobCommand;
import com.mdmesh.policies.persistence.domain.JobCount;
import com.mdmesh.policies.persistence.domain.JobPhone;
import com.mdmesh.policies.persistence.domain.JobRow;
import com.mdmesh.policies.persistence.domain.QueuedWake;
import com.mdmesh.policies.persistence.domain.RetryRow;
import com.mdmesh.policies.persistence.mapper.JobMapper;
import org.mybatis.guice.transactional.Transactional;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Bulk jobs in the database. Creating, cancelling and retrying are each one transaction: all or nothing. */
@Singleton
public class JobDAO {
    /** Rows per INSERT statement: big jobs go in a few statements, inside the one transaction. */
    static final int CHUNK = 500;

    private final JobMapper mapper;

    @Inject
    public JobDAO(JobMapper mapper) {
        this.mapper = mapper;
    }

    /** Stores the job, its phones and its commands at once. Returns the job id. */
    @Transactional
    public int create(JobRow job, List<String> numbers, List<JobCommand> commands) {
        mapper.insertJob(job);
        int id = job.getId();
        for (int i = 0; i < numbers.size(); i += CHUNK) {
            mapper.insertDevices(id, numbers.subList(i, Math.min(numbers.size(), i + CHUNK)));
        }
        for (int i = 0; i < commands.size(); i += CHUNK) {
            mapper.insertCommands(id, commands.subList(i, Math.min(commands.size(), i + CHUNK)));
        }
        return id;
    }

    /** Adds phones and their commands to a job (tonight's sweep grows its job a batch at a time). */
    @Transactional
    public void append(int jobId, List<String> numbers, List<JobCommand> commands) {
        for (int i = 0; i < numbers.size(); i += CHUNK) {
            mapper.insertDevices(jobId, numbers.subList(i, Math.min(numbers.size(), i + CHUNK)));
        }
        for (int i = 0; i < commands.size(); i += CHUNK) {
            mapper.insertCommands(jobId, commands.subList(i, Math.min(commands.size(), i + CHUNK)));
        }
    }

    /** Stops the job's wakes and expires its commands no phone has received. False when there is no such job to cancel. */
    @Transactional
    public boolean cancel(int id, int customerId, long now) {
        if (mapper.markCancelled(id, customerId) == 0) return false;
        mapper.cancelWakes(id);
        mapper.expireUndelivered(id, now);
        return true;
    }

    /** Sends an expired, never-delivered command again and queues its phone for a wake. Skipped if the job was cancelled. */
    @Transactional
    public boolean retry(RetryRow r, long now) {
        Boolean cancelled = mapper.lockCancelled(r.getJobId());
        if (cancelled == null || cancelled) return false;
        JobCommand c = new JobCommand(r.getDeviceNumber(), r.getType(), r.getPayload(), r.getRequiresCapability(), now);
        mapper.insertCommand(c);
        mapper.relink(r.getLinkId(), c.getId());
        mapper.requeue(r.getJobId(), r.getDeviceNumber());
        return true;
    }

    public List<JobPhone> listAccessible(int customerId, int userId, boolean superAdmin, Collection<Integer> ids) {
        if (ids.isEmpty()) return Collections.emptyList();
        return mapper.listAccessible(customerId, userId, superAdmin, ids);
    }

    public List<QueuedWake> nextQueued(int limit) {
        return mapper.nextQueued(limit);
    }

    public void markWake(Collection<Integer> ids, String wake, long at) {
        if (!ids.isEmpty()) mapper.markWake(ids, wake, at);
    }

    public List<RetryRow> listRetries(int maxAttempts, long since, int limit) {
        return mapper.listRetries(maxAttempts, since, limit);
    }

    /** Expires scheduled jobs' undelivered commands past their deadline, and stops waking their phones (last day only). */
    public int expireLate(long now) {
        long since = now - 24L * 60 * 60 * 1000;
        mapper.cancelLateWakes(now, since);
        return mapper.expireLate(now, since);
    }

    public int purge(long before) {
        return mapper.purge(before);
    }

    public List<JobRow> listJobs(int customerId, long since, int limit) {
        return mapper.listJobs(customerId, since, limit);
    }

    public JobRow getJob(int id, int customerId) {
        return mapper.getJob(id, customerId);
    }

    public List<JobCount> countWakes(Collection<Integer> ids) {
        return ids.isEmpty() ? Collections.<JobCount>emptyList() : mapper.countWakes(ids);
    }

    public List<JobCount> countCommands(Collection<Integer> ids) {
        return ids.isEmpty() ? Collections.<JobCount>emptyList() : mapper.countCommands(ids);
    }
}
