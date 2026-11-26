package com.mdmesh.policies.service;

import com.hmdm.rest.json.agent.CommandHistoryView;
import com.mdmesh.policies.persistence.JobDAO;
import com.mdmesh.policies.persistence.domain.AutomationSettings;
import com.mdmesh.policies.persistence.domain.FreshScan;
import com.mdmesh.policies.persistence.domain.JobCommand;
import com.mdmesh.policies.persistence.domain.JobPhone;
import com.mdmesh.policies.persistence.domain.JobRow;
import com.mdmesh.policies.persistence.domain.KeyValue;
import com.mdmesh.policies.persistence.mapper.AutomationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the server does by itself, once a minute (from {@link JobWorker}):
 *
 * <ul>
 *   <li><b>Nightly sweep</b> (with automatic scans on): inside the customer's window, the phones are scanned in id
 *   order, a batch a minute, sized to finish in the first half of the window. The cursor (the last id reached) moves
 *   one way only and goes back to the start the next night: C-LOOK. A phone scanned in the last 20 hours is skipped. All
 *   of a night's scans go into one job, "Nightly app scan", paced like any job.</li>
 *   <li><b>New phones</b> (with automatic scans on): phones never scanned are scanned at any time, a few a minute, at
 *   most once every 6 hours each until one scan succeeds.</li>
 *   <li><b>Auto-remove</b>: for phones on a kiosk policy set to Auto-remove, each scan that finished after the previous
 *   check is compared with the policy's allowed apps; the extra apps are uninstalled through one job, with the policy's
 *   alert. Only scans made after auto-remove started checking are ever used.</li>
 * </ul>
 */
@Singleton
public class AutomationService {
    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);

    static final long RECENT_SCAN_MS = 20L * 60 * 60 * 1000;
    static final long NEW_PHONE_RETRY_MS = 6L * 60 * 60 * 1000;
    static final int NEW_PHONES_PER_MINUTE = 5;
    static final int MAX_BATCH = 500;
    /** The cursor once tonight's sweep has reached every phone. */
    static final int SWEEP_DONE = Integer.MAX_VALUE;

    private final AutomationMapper mapper;
    private final JobService jobs;
    private final JobDAO dao;

    @Inject
    public AutomationService(AutomationMapper mapper, JobService jobs, JobDAO dao) {
        this.mapper = mapper;
        this.jobs = jobs;
        this.dao = dao;
    }

    // ---- settings ----

    public AutomationSettings settings(int customerId) {
        AutomationSettings s = mapper.getSettings(customerId);
        if (s != null) return s;
        s = new AutomationSettings();
        s.setCustomerId(customerId);
        return s;
    }

    public AutomationSettings save(int customerId, boolean automaticScans, int windowStart, int windowEnd, String timeZone) {
        if (windowStart < 0 || windowStart > 23 || windowEnd < 0 || windowEnd > 23) {
            throw new JobService.Refused("The scan window's hours go from 0 to 23.");
        }
        try {
            ZoneId.of(timeZone);
        } catch (DateTimeException | NullPointerException e) {
            throw new JobService.Refused("Unknown time zone: " + timeZone);
        }
        AutomationSettings s = new AutomationSettings();
        s.setCustomerId(customerId);
        s.setNightlyScan(automaticScans);
        s.setWindowStart(windowStart);
        s.setWindowEnd(windowEnd);
        s.setTimeZone(timeZone);
        mapper.saveSettings(s);
        return settings(customerId);
    }

    /** The settings with tonight's sweep progress, for the console. */
    public Map<String, Object> status(int customerId) {
        AutomationSettings s = settings(customerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("automaticScans", s.isNightlyScan());
        out.put("windowStart", s.getWindowStart());
        out.put("windowEnd", s.getWindowEnd());
        out.put("timeZone", s.getTimeZone());
        out.put("autoRemoveSince", s.getAutoRemoveSince());
        if (s.getSweepNight() != null) {
            int total = mapper.countPhones(customerId);
            boolean done = s.getSweepCursor() == SWEEP_DONE;
            Map<String, Object> night = new LinkedHashMap<>();
            night.put("night", s.getSweepNight());
            night.put("total", total);
            night.put("reached", done ? total : mapper.countPhonesUpTo(customerId, s.getSweepCursor()));
            night.put("finished", done);
            night.put("jobId", s.getSweepJobId());
            out.put("lastSweep", night);
        }
        return out;
    }

    // ---- the minute tick ----

    /** Runs every automation once. Each customer's step catches its own errors. */
    public void tick(long now) {
        for (AutomationSettings s : mapper.listScanning()) {
            try {
                sweep(s, now);
            } catch (Exception e) {
                log.warn("Policies plugin: nightly sweep for customer {} failed (retried next minute): {}", s.getCustomerId(), e.toString());
            }
            try {
                scanNewPhones(s.getCustomerId(), now);
            } catch (Exception e) {
                log.warn("Policies plugin: scanning new phones of customer {} failed: {}", s.getCustomerId(), e.toString());
            }
        }
        for (Integer customerId : mapper.listAutoRemoveCustomers()) {
            try {
                autoRemove(customerId, now);
            } catch (Exception e) {
                log.warn("Policies plugin: auto-remove for customer {} failed (retried next minute): {}", customerId, e.toString());
            }
        }
    }

    /** One minute of the C-LOOK sweep. Returns how many phones were added. */
    int sweep(AutomationSettings s, long now) {
        ZonedDateTime local = Instant.ofEpochMilli(now).atZone(zone(s.getTimeZone()));
        String night = nightOf(local, s.getWindowStart(), s.getWindowEnd());
        if (night == null) return 0;
        int customerId = s.getCustomerId();
        boolean sameNight = night.equals(s.getSweepNight());
        int cursor = sameNight ? s.getSweepCursor() : 0;
        Integer jobId = sameNight ? s.getSweepJobId() : null;
        if (cursor == SWEEP_DONE) return 0;
        if (jobId != null) {
            JobRow job = dao.getJob(jobId, customerId);
            if (job == null) {
                jobId = null;
            } else if (job.isCancelled()) {
                // Someone cancelled tonight's sweep: it stops until tomorrow night.
                mapper.saveSweep(customerId, night, SWEEP_DONE, jobId);
                return 0;
            }
        }
        int total = mapper.countPhones(customerId);
        int batch = batchSize(total, windowMinutes(s.getWindowStart(), s.getWindowEnd()));
        List<JobPhone> next = mapper.sweepBatch(customerId, cursor, now - RECENT_SCAN_MS, batch);
        if (next.isEmpty()) {
            mapper.saveSweep(customerId, night, SWEEP_DONE, jobId);
            return 0;
        }
        List<JobCommand> scans = scans(next, now);
        if (jobId == null) {
            jobId = jobs.createSystem(customerId, "scan", "Nightly app scan · " + night, next, scans, now);
        } else {
            jobs.appendSystem(jobId, next, scans);
        }
        mapper.saveSweep(customerId, night, next.get(next.size() - 1).getId(), jobId);
        return next.size();
    }

    int scanNewPhones(int customerId, long now) {
        List<JobPhone> phones = mapper.neverScanned(customerId, now - NEW_PHONE_RETRY_MS, NEW_PHONES_PER_MINUTE);
        if (phones.isEmpty()) return 0;
        jobs.createSystem(customerId, "scan", "Scan new phones", phones, scans(phones, now), now);
        return phones.size();
    }

    /** Checks the scans that finished since the last check. Returns how many apps it queued for removal. */
    int autoRemove(int customerId, long now) {
        AutomationSettings s = mapper.getSettings(customerId);
        Long since = s == null ? null : s.getAutoRemoveSince();
        if (since == null) {
            // First run: start from now, so scans made before auto-remove was checking are never acted on.
            mapper.saveAutoRemoveSince(customerId, now);
            return 0;
        }
        List<FreshScan> scans = mapper.freshScans(customerId, since, now);
        int queued = 0;
        if (!scans.isEmpty()) {
            Set<Integer> configIds = new HashSet<>();
            List<String> numbers = new ArrayList<>();
            for (FreshScan f : scans) {
                if (f.getConfigurationId() != null) configIds.add(f.getConfigurationId());
                numbers.add(f.getNumber());
            }
            Map<String, AutoRemoveRules.Rules> rules = new HashMap<>();
            for (KeyValue kv : mapper.policyData(configIds)) rules.put(kv.getKey(), AutoRemoveRules.parse(kv.getValue()));
            Map<String, Set<String>> allowed = group(mapper.allowedPackages(configIds), false);
            Map<String, Set<String>> removing = group(mapper.openUninstalls(numbers), true);

            List<JobPhone> phones = new ArrayList<>();
            List<JobCommand> commands = new ArrayList<>();
            for (FreshScan f : scans) {
                String cfg = String.valueOf(f.getConfigurationId());
                AutoRemoveRules.Rules r = rules.get(cfg);
                Set<String> allow = allowed.get(cfg);
                // A kiosk policy with no allowed app at all is left alone: that's a broken list, not a reason to empty phones.
                if (r == null || allow == null || allow.isEmpty()) continue;
                Set<String> busy = removing.get(f.getNumber());
                Map<String, String> extras = AutoRemoveRules.extras(f.getScanDetail(), allow, r, f.getNumber(),
                        busy == null ? new HashSet<String>() : busy);
                if (extras.isEmpty()) continue;
                phones.add(f);
                for (String pkg : extras.keySet()) {
                    commands.add(new JobCommand(f.getNumber(), "app.uninstall", JobService.json("packageName", pkg, null, null), null, now));
                }
                if (r.notify) {
                    String[] a = AutoRemoveRules.alert(AutoRemoveRules.labels(extras));
                    commands.add(new JobCommand(f.getNumber(), "device.alert",
                            JobService.json("title", a[0], "body", JobService.clip(a[1], 500)), "device.alert", now));
                }
                queued += extras.size();
            }
            if (!phones.isEmpty()) {
                jobs.createSystem(customerId, "remove", "Auto-remove extra apps", phones, commands, now);
                log.info("Policies plugin: auto-remove queued {} apps on {} phones (customer {})", queued, phones.size(), customerId);
            }
        }
        mapper.saveAutoRemoveSince(customerId, now);
        return queued;
    }

    // ---- helpers ----

    private static List<JobCommand> scans(List<JobPhone> phones, long now) {
        List<JobCommand> out = new ArrayList<>();
        for (JobPhone p : phones) out.add(new JobCommand(p.getNumber(), "apps.scan", null, null, now));
        return out;
    }

    /** Key to its values; for open uninstalls the value is a payload, read as its package. */
    private static Map<String, Set<String>> group(List<KeyValue> rows, boolean payloads) {
        Map<String, Set<String>> out = new HashMap<>();
        for (KeyValue kv : rows) {
            String v = payloads ? CommandHistoryView.subjectOf("app.uninstall", kv.getValue()) : kv.getValue();
            if (v == null) continue;
            Set<String> s = out.get(kv.getKey());
            if (s == null) out.put(kv.getKey(), s = new LinkedHashSet<>());
            s.add(v.trim());
        }
        return out;
    }

    static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }

    /**
     * The night a moment belongs to (the date its window started), or null outside the window. A window may cross
     * midnight (22 to 4): then 02:00 belongs to the night that started the day before. Equal hours mean all day.
     */
    static String nightOf(ZonedDateTime t, int start, int end) {
        int h = t.getHour();
        LocalDate date = t.toLocalDate();
        if (start == end) return date.toString();
        if (start < end) return h >= start && h < end ? date.toString() : null;
        if (h >= start) return date.toString();
        if (h < end) return date.minusDays(1).toString();
        return null;
    }

    static int windowMinutes(int start, int end) {
        int hours = ((end - start) % 24 + 24) % 24;
        return (hours == 0 ? 24 : hours) * 60;
    }

    /** Phones a minute so the sweep ends within the first half of the window, at least 1, at most {@link #MAX_BATCH}. */
    static int batchSize(int phones, int windowMinutes) {
        int minutes = Math.max(1, windowMinutes / 2);
        return Math.max(1, Math.min(MAX_BATCH, (phones + minutes - 1) / minutes));
    }
}
