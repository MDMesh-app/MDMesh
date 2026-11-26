package com.mdmesh.policies.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mdmesh.policies.persistence.JobDAO;
import com.mdmesh.policies.persistence.domain.JobCommand;
import com.mdmesh.policies.persistence.domain.JobCount;
import com.mdmesh.policies.persistence.domain.JobPhone;
import com.mdmesh.policies.persistence.domain.JobRow;
import com.mdmesh.policies.rest.json.JobRequest;
import com.mdmesh.policies.rest.json.JobView;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Creates, lists and cancels bulk jobs. The worker ({@link JobWorker}) does the paced part. */
@Singleton
public class JobService {
    /** The most phones one job takes. */
    static final int MAX_PHONES = 10000;
    static final int MAX_PACKAGES = 200;
    static final int MAX_PAYLOAD = 4096;

    /**
     * The remote actions a job may send, with the capability each requires (fixed here, not taken from the request):
     * the device page's own actions. Wipe and passcode reset are never sent in bulk, as in core.
     */
    static final Map<String, String> COMMANDS;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("device.lock", "device.lock");
        m.put("device.ring", "device.ring");
        m.put("device.ringStop", "device.ringStop");
        m.put("device.reboot", "device.reboot");
        m.put("kiosk.exit", null);
        m.put("device.powerMode", "device.powerMode");
        m.put("device.locationMode", "device.locationMode");
        m.put("device.alert", "device.alert");
        m.put("device.lockscreenMessage", "device.lockscreenMessage");
        // Open an app (agent protocol 1.13): only phones whose agent reports the capability get it.
        m.put("app.launch", "app.launch");
        COMMANDS = Collections.unmodifiableMap(m);
    }

    private static final Pattern PKG = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JobDAO dao;
    private final CoreInstaller installer;

    @Inject
    public JobService(JobDAO dao, CoreInstaller installer) {
        this.dao = dao;
        this.installer = installer;
    }

    /** A request the server refuses, with the reason the console shows. */
    public static class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    public JobView create(int customerId, int userId, boolean superAdmin, JobRequest req, long now) {
        if (req == null || req.action == null) throw new Refused("No action given.");
        String action = req.action;
        List<Integer> ids = new ArrayList<>(new LinkedHashSet<>("remove".equals(action) ? removalIds(req) : nonNull(req.deviceIds)));
        if (ids.isEmpty()) throw new Refused("No phones given.");
        if (ids.size() > MAX_PHONES) throw new Refused("A job takes at most " + MAX_PHONES + " phones.");

        // Access is checked as core checks it; one phone the user may not access refuses the whole job, as core's bulk does.
        List<JobPhone> phones = dao.listAccessible(customerId, userId, superAdmin, ids);
        if (phones.size() != ids.size()) throw new Refused("Some of these phones don't exist or you may not manage them.");
        Map<Integer, JobPhone> byId = new LinkedHashMap<>();
        for (JobPhone p : phones) byId.put(p.getId(), p);
        // A phone core marked wiped erased itself and takes no command (core's AdminCommandPolicy): left out.
        ids.removeIf(i -> byId.get(i).getWipedAt() != null);
        if (ids.isEmpty()) throw new Refused("These phones were wiped: they take no more commands.");

        List<JobCommand> commands = new ArrayList<>();
        List<Integer> targets = ids;
        switch (action) {
            case "reapply":
                break;
            case "scan":
                for (Integer id : ids) commands.add(new JobCommand(byId.get(id).getNumber(), "apps.scan", null, null, now));
                break;
            case "command": {
                String type = req.command == null ? null : req.command.type;
                if (type == null || !COMMANDS.containsKey(type)) throw new Refused("That action can't be sent from a job.");
                String payload = req.command.payload;
                if (payload != null && payload.length() > MAX_PAYLOAD) throw new Refused("The action's settings are too long.");
                for (Integer id : ids) commands.add(new JobCommand(byId.get(id).getNumber(), type, payload, COMMANDS.get(type), now));
                break;
            }
            case "install":
                if (!installer.available()) throw new Refused("Installing policy apps from a job isn't available on this server.");
                for (Integer id : ids) commands.addAll(installer.commands(byId.get(id), now));
                break;
            case "remove":
                for (JobRequest.Removal r : nonNull(req.removals)) {
                    if (r == null || r.deviceId == null || !ids.contains(r.deviceId)) continue;
                    String number = byId.get(r.deviceId).getNumber();
                    List<String> pkgs = new ArrayList<>(new LinkedHashSet<>(nonNull(r.packages)));
                    if (pkgs.size() > MAX_PACKAGES) throw new Refused("Too many apps for one phone.");
                    for (String pkg : pkgs) {
                        if (!PKG.matcher(pkg).matches()) throw new Refused("Not a package name: " + pkg);
                        commands.add(new JobCommand(number, "app.uninstall", json("packageName", pkg, null, null), null, now));
                    }
                    if (!pkgs.isEmpty() && r.alertBody != null && !r.alertBody.trim().isEmpty()) {
                        commands.add(new JobCommand(number, "device.alert",
                                json("title", clip(r.alertTitle, 100), "body", clip(r.alertBody, 500)), "device.alert", now));
                    }
                }
                break;
            default:
                throw new Refused("Unknown action: " + action);
        }

        JobRow job = new JobRow();
        job.setCustomerId(customerId);
        job.setCreatedBy(userId);
        job.setAction(action);
        job.setLabel(clip(req.label, 200));
        job.setCreatedAt(now);
        List<String> numbers = new ArrayList<>();
        for (Integer id : targets) numbers.add(byId.get(id).getNumber());
        int id = dao.create(job, numbers, commands);
        return view(dao.getJob(id, customerId));
    }

    /**
     * A job the server starts itself (nightly sweep, new phones, auto-remove): no user, so no access check; the phones
     * are the customer's own, as the automation's queries pick them. Returns the job id.
     */
    public int createSystem(int customerId, String action, String label, List<JobPhone> phones, List<JobCommand> commands, long now) {
        JobRow job = new JobRow();
        job.setCustomerId(customerId);
        job.setAction(action);
        job.setLabel(clip(label, 200));
        job.setCreatedAt(now);
        return dao.create(job, numbers(phones), commands);
    }

    /** Adds phones and commands to a job the server started. */
    public void appendSystem(int jobId, List<JobPhone> phones, List<JobCommand> commands) {
        dao.append(jobId, numbers(phones), commands);
    }

    private static List<String> numbers(List<JobPhone> phones) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (JobPhone p : phones) out.add(p.getNumber());
        return new ArrayList<>(out);
    }

    /** The customer's jobs of the last 24 hours, newest first. */
    public List<JobView> recent(int customerId, long now) {
        List<JobRow> rows = dao.listJobs(customerId, now - 24L * 60 * 60 * 1000, 20);
        return views(rows);
    }

    public JobView cancel(int customerId, int jobId, long now) {
        JobRow job = dao.getJob(jobId, customerId);
        if (job == null) throw new Refused("No such job.");
        dao.cancel(jobId, customerId, now);
        return view(dao.getJob(jobId, customerId));
    }

    JobView view(JobRow row) {
        return row == null ? null : views(Collections.singletonList(row)).get(0);
    }

    /** Progress for these jobs in two grouped queries (not per job). */
    List<JobView> views(List<JobRow> rows) {
        List<Integer> ids = new ArrayList<>();
        for (JobRow r : rows) ids.add(r.getId());
        Map<Integer, Map<String, Integer>> wakes = group(dao.countWakes(ids));
        Map<Integer, Map<String, Integer>> cmds = group(dao.countCommands(ids));
        List<JobView> out = new ArrayList<>();
        for (JobRow r : rows) {
            JobView v = new JobView();
            v.id = r.getId();
            v.action = r.getAction();
            v.label = r.getLabel();
            v.createdAt = r.getCreatedAt() == null ? 0 : r.getCreatedAt();
            v.cancelled = r.isCancelled();
            v.wake = orEmpty(wakes.get(r.getId()));
            v.commands = orEmpty(cmds.get(r.getId()));
            int phones = 0;
            for (int n : v.wake.values()) phones += n;
            v.phones = phones;
            v.phase = phase(v);
            out.add(v);
        }
        return out;
    }

    static String phase(JobView v) {
        if (v.cancelled) return "cancelled";
        if (count(v.wake, "queued") > 0) return "sending";
        if (count(v.commands, "delivered") + count(v.commands, "accepted") > 0) return "running";
        if (count(v.commands, "pending") > 0) {
            // Pending after every phone's wake: the online ones are about to pick theirs up; only offline ones wait.
            return count(v.wake, "woken") > 0 && count(v.wake, "offline") == 0 ? "running" : "waiting";
        }
        return "done";
    }

    private static int count(Map<String, Integer> m, String k) {
        Integer n = m.get(k);
        return n == null ? 0 : n;
    }

    private static Map<Integer, Map<String, Integer>> group(List<JobCount> counts) {
        Map<Integer, Map<String, Integer>> out = new HashMap<>();
        for (JobCount c : counts) {
            Map<String, Integer> m = out.get(c.getJobId());
            if (m == null) out.put(c.getJobId(), m = new LinkedHashMap<>());
            m.put(c.getState(), c.getCount());
        }
        return out;
    }

    private static Map<String, Integer> orEmpty(Map<String, Integer> m) {
        return m == null ? new LinkedHashMap<String, Integer>() : m;
    }

    private static List<Integer> removalIds(JobRequest req) {
        List<Integer> ids = new ArrayList<>();
        for (JobRequest.Removal r : nonNull(req.removals)) if (r != null && r.deviceId != null) ids.add(r.deviceId);
        return ids;
    }

    private static <T> List<T> nonNull(List<T> list) {
        if (list == null) return Collections.emptyList();
        List<T> out = new ArrayList<>();
        for (T t : list) if (t != null) out.add(t);
        return out;
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    static String json(String k1, String v1, String k2, String v2) {
        ObjectNode n = JSON.createObjectNode();
        if (v1 != null) n.put(k1, v1);
        if (k2 != null && v2 != null) n.put(k2, v2);
        return n.toString();
    }

}
