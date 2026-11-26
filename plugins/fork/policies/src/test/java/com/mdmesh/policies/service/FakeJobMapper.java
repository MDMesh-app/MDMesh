package com.mdmesh.policies.service;

import com.mdmesh.policies.persistence.domain.JobCommand;
import com.mdmesh.policies.persistence.domain.JobCount;
import com.mdmesh.policies.persistence.domain.JobPhone;
import com.mdmesh.policies.persistence.domain.JobRow;
import com.mdmesh.policies.persistence.domain.QueuedWake;
import com.mdmesh.policies.persistence.domain.RetryRow;
import com.mdmesh.policies.persistence.mapper.JobMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The job tables and core's agentCommand rows, in memory, behaving as the SQL in JobMapper does. */
public class FakeJobMapper implements JobMapper {
    /** Phones the user may access, by id. */
    public final Map<Integer, JobPhone> accessible = new LinkedHashMap<>();
    public final Map<Integer, JobRow> jobs = new TreeMap<>();
    public final Map<Integer, Device> devices = new TreeMap<>();
    /** core's agentCommand rows. */
    public final Map<Integer, Cmd> commands = new TreeMap<>();
    public final Map<Integer, Link> links = new TreeMap<>();
    private int seq = 1;

    public static class Device {
        public int id, jobId;
        public String number, wake = "queued";
        public Long wokenAt;
    }

    public static class Cmd {
        public int id;
        public String deviceNumber, type, payload, capability, status, detail;
        public Long deliveredAt, completedAt;
    }

    public static class Link {
        public int id, jobId, commandId, attempts = 1;
        public String deviceNumber;
    }

    public void phone(int id, String number, Integer cfg) {
        phone(id, number, cfg, null);
    }

    public void phone(int id, String number, Integer cfg, String agentVersion) {
        phone(id, number, cfg, agentVersion, null);
    }

    public void phone(int id, String number, Integer cfg, String agentVersion, Long wipedAt) {
        JobPhone p = new JobPhone();
        p.setWipedAt(wipedAt);
        p.setAgentVersion(agentVersion);
        p.setId(id);
        p.setNumber(number);
        p.setConfigurationId(cfg);
        p.setCustomerId(1);
        accessible.put(id, p);
    }

    public List<Cmd> commandsOf(int jobId) {
        List<Cmd> out = new ArrayList<>();
        for (Link l : links.values()) if (l.jobId == jobId) out.add(commands.get(l.commandId));
        return out;
    }

    public List<Device> devicesOf(int jobId) {
        List<Device> out = new ArrayList<>();
        for (Device d : devices.values()) if (d.jobId == jobId) out.add(d);
        return out;
    }

    @Override
    public List<JobPhone> listAccessible(int customerId, int userId, boolean superAdmin, Collection<Integer> ids) {
        List<JobPhone> out = new ArrayList<>();
        for (Integer id : ids) if (accessible.containsKey(id)) out.add(accessible.get(id));
        return out;
    }

    @Override
    public void insertJob(JobRow job) {
        job.setId(seq++);
        jobs.put(job.getId(), job);
    }

    @Override
    public void insertDevices(int jobId, List<String> numbers) {
        for (String n : numbers) {
            Device d = new Device();
            d.id = seq++;
            d.jobId = jobId;
            d.number = n;
            devices.put(d.id, d);
        }
    }

    @Override
    public void insertCommands(int jobId, List<JobCommand> list) {
        for (JobCommand c : list) {
            insertCommand(c);
            Link l = new Link();
            l.id = seq++;
            l.jobId = jobId;
            l.commandId = c.getId();
            l.deviceNumber = c.getDeviceNumber();
            links.put(l.id, l);
        }
    }

    @Override
    public List<QueuedWake> nextQueued(int limit) {
        List<QueuedWake> out = new ArrayList<>();
        for (Device d : devices.values()) {
            if (out.size() >= limit) break;
            if (!"queued".equals(d.wake)) continue;
            QueuedWake q = new QueuedWake();
            q.setId(d.id);
            q.setJobId(d.jobId);
            q.setDeviceNumber(d.number);
            out.add(q);
        }
        return out;
    }

    @Override
    public int markWake(Collection<Integer> ids, String wake, long at) {
        int n = 0;
        for (Integer id : ids) {
            Device d = devices.get(id);
            if (d != null && "queued".equals(d.wake)) {
                d.wake = wake;
                d.wokenAt = at;
                n++;
            }
        }
        return n;
    }

    @Override
    public List<RetryRow> listRetries(int maxAttempts, long since, int limit) {
        List<RetryRow> out = new ArrayList<>();
        for (Link l : links.values()) {
            Cmd c = commands.get(l.commandId);
            JobRow j = jobs.get(l.jobId);
            if (!"expired".equals(c.status) || c.deliveredAt != null || j.isCancelled() || j.getDeadline() != null
                    || l.attempts >= maxAttempts
                    || j.getCreatedAt() < since) continue;
            RetryRow r = new RetryRow();
            r.setLinkId(l.id);
            r.setJobId(l.jobId);
            r.setAttempts(l.attempts);
            r.setDeviceNumber(c.deviceNumber);
            r.setType(c.type);
            r.setPayload(c.payload);
            r.setRequiresCapability(c.capability);
            out.add(r);
        }
        return out;
    }

    @Override
    public void insertCommand(JobCommand c) {
        Cmd row = new Cmd();
        row.id = seq++;
        row.deviceNumber = c.getDeviceNumber();
        row.type = c.getType();
        row.payload = c.getPayload();
        row.capability = c.getRequiresCapability();
        row.status = c.getStatus();
        row.detail = c.getDetail();
        commands.put(row.id, row);
        c.setId(row.id);
    }

    @Override
    public Boolean lockCancelled(int id) {
        JobRow j = jobs.get(id);
        return j == null ? null : j.isCancelled();
    }

    @Override
    public void relink(int linkId, int commandId) {
        Link l = links.get(linkId);
        l.commandId = commandId;
        l.attempts++;
    }

    @Override
    public void requeue(int jobId, String deviceNumber) {
        for (Device d : devices.values()) {
            if (d.jobId == jobId && d.number.equals(deviceNumber) && !"queued".equals(d.wake) && !"cancelled".equals(d.wake)) {
                d.wake = "queued";
                d.wokenAt = null;
            }
        }
    }

    @Override
    public int purge(long before) {
        return 0;
    }

    @Override
    public int expireLate(long now, long since) {
        int n = 0;
        for (Link l : links.values()) {
            JobRow j = jobs.get(l.jobId);
            Cmd c = commands.get(l.commandId);
            if (j.getDeadline() != null && j.getDeadline() < now && j.getCreatedAt() > since
                    && "pending".equals(c.status) && c.deliveredAt == null) {
                c.status = "expired";
                c.detail = "Too late for its schedule: skipped";
                n++;
            }
        }
        return n;
    }

    @Override
    public int cancelLateWakes(long now, long since) {
        int n = 0;
        for (Device d : devices.values()) {
            JobRow j = jobs.get(d.jobId);
            if ("queued".equals(d.wake) && j.getDeadline() != null && j.getDeadline() < now && j.getCreatedAt() > since) {
                d.wake = "cancelled";
                n++;
            }
        }
        return n;
    }

    @Override
    public List<JobRow> listJobs(int customerId, long since, int limit) {
        List<JobRow> out = new ArrayList<>(jobs.values());
        java.util.Collections.reverse(out);
        return out;
    }

    @Override
    public JobRow getJob(int id, int customerId) {
        JobRow j = jobs.get(id);
        return j != null && j.getCustomerId() == customerId ? j : null;
    }

    @Override
    public List<JobCount> countWakes(Collection<Integer> ids) {
        Map<String, JobCount> m = new LinkedHashMap<>();
        for (Device d : devices.values()) if (ids.contains(d.jobId)) bump(m, d.jobId, d.wake);
        return new ArrayList<>(m.values());
    }

    @Override
    public List<JobCount> countCommands(Collection<Integer> ids) {
        Map<String, JobCount> m = new LinkedHashMap<>();
        for (Link l : links.values()) if (ids.contains(l.jobId)) bump(m, l.jobId, commands.get(l.commandId).status);
        return new ArrayList<>(m.values());
    }

    private static void bump(Map<String, JobCount> m, int jobId, String state) {
        String k = jobId + ":" + state;
        JobCount c = m.get(k);
        if (c == null) {
            c = new JobCount();
            c.setJobId(jobId);
            c.setState(state);
            c.setCount(0);
            m.put(k, c);
        }
        c.setCount(c.getCount() + 1);
    }

    @Override
    public int markCancelled(int id, int customerId) {
        JobRow j = getJob(id, customerId);
        if (j == null || j.isCancelled()) return 0;
        j.setCancelled(true);
        return 1;
    }

    @Override
    public int cancelWakes(int jobId) {
        int n = 0;
        for (Device d : devices.values()) {
            if (d.jobId == jobId && "queued".equals(d.wake)) {
                d.wake = "cancelled";
                n++;
            }
        }
        return n;
    }

    @Override
    public int expireUndelivered(int jobId, long now) {
        int n = 0;
        for (Cmd c : commandsOf(jobId)) {
            if ("pending".equals(c.status) && c.deliveredAt == null) {
                c.status = "expired";
                c.detail = "Cancelled from the Policies page";
                n++;
            }
        }
        return n;
    }
}
