package com.mdmesh.policies.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.util.AgentCapabilityTokens;
import com.mdmesh.policies.persistence.JobDAO;
import com.mdmesh.policies.persistence.domain.AppSchedule;
import com.mdmesh.policies.persistence.domain.GroupLink;
import com.mdmesh.policies.persistence.domain.JobCommand;
import com.mdmesh.policies.persistence.domain.JobRow;
import com.mdmesh.policies.persistence.domain.SchedulePhone;
import com.mdmesh.policies.persistence.mapper.ScheduleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * App schedules: open an app on a policy's phones at set times ({@code app.launch}), like an alarm. Nothing locks the
 * app; the user leaves it when they want.
 *
 * <ul>
 *   <li><b>Who:</b> every phone of the policy, the policy's phones in one group (placed in groups exactly as the
 *   console does: picked by hand first, else by brand, so phones that join the group later are included), or phones
 *   picked by hand.</li>
 *   <li>Only phones whose agent reports the {@code launch} capability get the command; the others are counted as
 *   skipped (core would never deliver it to them anyway).</li>
 *   <li><b>Once, on time:</b> a slot is claimed in the database before it fires (once, even across restarts), and its
 *   job has a deadline {@code lateMinutes} after the slot: a phone offline at the time still gets it if it comes online
 *   before then; after that its command expires, so an app never opens hours late.</li>
 * </ul>
 */
@Singleton
public class ScheduleService {
    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    static final Pattern PKG = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");
    static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final int MAX_TIMES = 24;
    static final int MAX_LATE = 240;
    static final int MAX_PHONES = 10000;
    /** The capability token a phone reports when it can open an app on request. */
    static final String LAUNCH_CAPABILITY = "app.launch";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ScheduleMapper mapper;
    private final JobDAO jobs;

    @Inject
    public ScheduleService(ScheduleMapper mapper, JobDAO jobs) {
        this.mapper = mapper;
        this.jobs = jobs;
    }

    /** A schedule as the console saves it. */
    public static class Input {
        public String pkg;
        public String appName;
        /** policy, group or phones. */
        public String target = "policy";
        public Integer groupId;
        public List<Integer> devices = new ArrayList<>();
        public int days;
        public List<String> times = new ArrayList<>();
        public String timeZone;
        public int lateMinutes = 15;
        public boolean enabled = true;
    }

    public List<Map<String, Object>> list(int customerId, int configurationId, long now) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppSchedule s : mapper.list(customerId, configurationId)) out.add(view(s, now));
        return out;
    }

    public Map<String, Object> save(int customerId, int configurationId, Integer id, Input in, long now) {
        if (mapper.countConfiguration(customerId, configurationId) == 0) throw new JobService.Refused("No such policy.");
        AppSchedule s = id == null ? new AppSchedule() : mapper.get(id, customerId);
        if (s == null || (id != null && s.getConfigurationId() != configurationId)) throw new JobService.Refused("No such schedule.");
        apply(s, in);
        if ("group".equals(s.getTarget()) && mapper.groupName(customerId, s.getGroupId()) == null) {
            throw new JobService.Refused("No such group.");
        }
        s.setCustomerId(customerId);
        if (id == null) {
            s.setConfigurationId(configurationId);
            s.setCreatedAt(now);
            mapper.insert(s);
        } else {
            mapper.update(s);
        }
        return view(mapper.get(s.getId(), customerId), now);
    }

    public void delete(int customerId, int id) {
        if (mapper.delete(id, customerId) == 0) throw new JobService.Refused("No such schedule.");
    }

    /** Checks the input and copies it onto the schedule. */
    static void apply(AppSchedule s, Input in) {
        if (in == null) throw new JobService.Refused("Nothing to save.");
        String pkg = in.pkg == null ? "" : in.pkg.trim();
        if (!PKG.matcher(pkg).matches()) throw new JobService.Refused("Pick the app to open.");
        String target = in.target == null ? "" : in.target;
        Integer groupId = null;
        String devices = null;
        switch (target) {
            case "policy":
                break;
            case "group":
                if (in.groupId == null) throw new JobService.Refused("Pick the group.");
                groupId = in.groupId;
                break;
            case "phones": {
                Set<Integer> ids = new LinkedHashSet<>();
                if (in.devices != null) for (Integer d : in.devices) if (d != null && d > 0) ids.add(d);
                if (ids.isEmpty()) throw new JobService.Refused("Pick at least one phone.");
                if (ids.size() > MAX_PHONES) throw new JobService.Refused("Too many phones (" + MAX_PHONES + " at most).");
                StringBuilder b = new StringBuilder();
                for (Integer d : ids) b.append(b.length() == 0 ? "" : ",").append(d);
                devices = b.toString();
                break;
            }
            default:
                throw new JobService.Refused("Choose who it opens on: the whole policy, a group or picked phones.");
        }
        if (in.days < 1 || in.days > 127) throw new JobService.Refused("Pick at least one day.");
        TreeSet<String> times = new TreeSet<>();
        for (String t : in.times == null ? new ArrayList<String>() : in.times) {
            String v = t == null ? "" : t.trim();
            if (!TIME.matcher(v).matches()) throw new JobService.Refused("Times are HH:MM, like 09:00: " + t);
            times.add(v);
        }
        if (times.isEmpty()) throw new JobService.Refused("Add at least one time.");
        if (times.size() > MAX_TIMES) throw new JobService.Refused("At most " + MAX_TIMES + " times a day.");
        try {
            ZoneId.of(in.timeZone);
        } catch (DateTimeException | NullPointerException e) {
            throw new JobService.Refused("Unknown time zone: " + in.timeZone);
        }
        if (in.lateMinutes < 1 || in.lateMinutes > MAX_LATE) {
            throw new JobService.Refused("A phone may get it 1 to " + MAX_LATE + " minutes late.");
        }
        s.setPkg(pkg);
        s.setAppName(JobService.clip(in.appName, 100));
        s.setTarget(target);
        s.setGroupId(groupId);
        s.setDevices(devices);
        s.setDays(in.days);
        s.setTimes(String.join(",", times));
        s.setTimeZone(in.timeZone);
        s.setLateMinutes(in.lateMinutes);
        s.setEnabled(in.enabled);
    }

    Map<String, Object> view(AppSchedule s, long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.getId());
        out.put("configurationId", s.getConfigurationId());
        out.put("pkg", s.getPkg());
        out.put("appName", s.getAppName());
        out.put("target", s.getTarget());
        out.put("groupId", s.getGroupId());
        out.put("devices", deviceIds(s));
        out.put("days", s.getDays());
        out.put("times", times(s));
        out.put("timeZone", s.getTimeZone());
        out.put("lateMinutes", s.getLateMinutes());
        out.put("enabled", s.isEnabled());
        out.put("lastSlot", s.getLastSlot());
        out.put("nextRun", s.isEnabled() ? nextRun(s, now) : null);
        return out;
    }

    static List<Integer> deviceIds(AppSchedule s) {
        List<Integer> out = new ArrayList<>();
        if (s.getDevices() == null || s.getDevices().isEmpty()) return out;
        for (String d : s.getDevices().split(",")) {
            try {
                out.add(Integer.parseInt(d.trim()));
            } catch (NumberFormatException ignored) {
                // a hand-edited row: skip the bad id
            }
        }
        return out;
    }

    // ---- when ----

    static List<String> times(AppSchedule s) {
        List<String> out = new ArrayList<>();
        if (s.getTimes() == null) return out;
        for (String t : s.getTimes().split(",")) if (TIME.matcher(t.trim()).matches()) out.add(t.trim());
        return out;
    }

    static boolean dayAllowed(int days, DayOfWeek d) {
        return (days & (1 << (d.getValue() - 1))) != 0;
    }

    static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }

    /**
     * The slot to fire now ("yyyy-MM-dd HH:mm" in the schedule's zone): the latest one whose time has come and is less
     * than lateMinutes old, on an allowed day, and later than the last one fired; else null. Yesterday is looked at
     * too, for a slot just before midnight.
     */
    static String dueSlot(AppSchedule s, long now) {
        ZoneId z = zone(s.getTimeZone());
        LocalDate today = Instant.ofEpochMilli(now).atZone(z).toLocalDate();
        long lateMs = s.getLateMinutes() * 60_000L;
        String best = null;
        for (int offset = -1; offset <= 0; offset++) {
            LocalDate date = today.plusDays(offset);
            if (!dayAllowed(s.getDays(), date.getDayOfWeek())) continue;
            for (String t : times(s)) {
                ZonedDateTime at = date.atTime(LocalTime.parse(t)).atZone(z);
                long ms = at.toInstant().toEpochMilli();
                if (ms <= now && now < ms + lateMs) {
                    String key = at.format(SLOT);
                    if (best == null || key.compareTo(best) > 0) best = key;
                }
            }
        }
        if (best == null) return null;
        return s.getLastSlot() != null && best.compareTo(s.getLastSlot()) <= 0 ? null : best;
    }

    /** When the schedule next fires (epoch ms), within the coming 8 days; null if never. */
    static Long nextRun(AppSchedule s, long now) {
        ZoneId z = zone(s.getTimeZone());
        LocalDate today = Instant.ofEpochMilli(now).atZone(z).toLocalDate();
        for (int offset = 0; offset <= 8; offset++) {
            LocalDate date = today.plusDays(offset);
            if (!dayAllowed(s.getDays(), date.getDayOfWeek())) continue;
            for (String t : times(s)) {
                long ms = date.atTime(LocalTime.parse(t)).atZone(z).toInstant().toEpochMilli();
                if (ms > now) return ms;
            }
        }
        return null;
    }

    // ---- who ----

    /** Its hardware brand and manufacturer, from its telemetry; nulls when it has none or it can't be read. */
    static String[] reported(String telemetry) {
        if (telemetry == null || telemetry.isEmpty()) return new String[]{null, null};
        try {
            JsonNode hw = JSON.readTree(telemetry).path("hardware");
            return new String[]{text(hw.get("brand")), text(hw.get("manufacturer"))};
        } catch (Exception e) {
            return new String[]{null, null};
        }
    }

    private static String text(JsonNode n) {
        return n != null && n.isTextual() ? n.asText() : null;
    }

    /** The phones a schedule reaches, of those on its policy. */
    List<SchedulePhone> targets(AppSchedule s, List<SchedulePhone> phones) {
        switch (s.getTarget() == null ? "" : s.getTarget()) {
            case "policy":
                return phones;
            case "phones": {
                Set<Integer> ids = new LinkedHashSet<>(deviceIds(s));
                List<SchedulePhone> out = new ArrayList<>();
                for (SchedulePhone p : phones) if (ids.contains(p.getId())) out.add(p);
                return out;
            }
            case "group": {
                List<SchedulePhone> out = new ArrayList<>();
                if (s.getGroupId() == null) return out;
                Map<String, Integer> groupOfBrand = new HashMap<>();
                for (GroupLink l : mapper.groupBrands(s.getCustomerId())) groupOfBrand.putIfAbsent(l.getBrand(), l.getGroupId());
                for (SchedulePhone p : phones) {
                    Integer group = p.getPickedGroupId();
                    if (group == null) {
                        String[] r = reported(p.getTelemetry());
                        group = groupOfBrand.get(BrandRules.of(p.getSetBrand(), r[0], r[1], p.getDescription()));
                    }
                    if (s.getGroupId().equals(group)) out.add(p);
                }
                return out;
            }
            default:
                return new ArrayList<>();
        }
    }

    // ---- firing ----

    /** Fires every schedule whose slot is due. Each schedule catches its own errors. Returns how many fired. */
    public int tick(long now) {
        int fired = 0;
        for (AppSchedule s : mapper.listEnabled()) {
            try {
                String slot = dueSlot(s, now);
                if (slot != null && mapper.claimSlot(s.getId(), slot) == 1) {
                    fire(s, slot, now);
                    fired++;
                }
            } catch (Exception e) {
                log.warn("Policies plugin: schedule {} did not fire: {}", s.getId(), e.toString());
            }
        }
        return fired;
    }

    /** Sends the slot's opens as one job, with a deadline lateMinutes after the slot. */
    void fire(AppSchedule s, String slot, long now) {
        long slotAt = ZonedDateTime.of(LocalDateTime.parse(slot, SLOT), zone(s.getTimeZone())).toInstant().toEpochMilli();
        List<String> numbers = new ArrayList<>();
        List<JobCommand> commands = new ArrayList<>();
        int skipped = 0;
        String payload = JobService.json("packageName", s.getPkg(), null, null);
        for (SchedulePhone p : targets(s, mapper.phones(s.getCustomerId(), s.getConfigurationId()))) {
            if (!AgentCapabilityTokens.isAllowed(LAUNCH_CAPABILITY, AgentCapabilityTokens.flatten(p.getCapabilities()))) {
                skipped++;
                continue;
            }
            numbers.add(p.getNumber());
            commands.add(new JobCommand(p.getNumber(), "app.launch", payload, LAUNCH_CAPABILITY, now));
        }
        if (commands.isEmpty()) {
            log.info("Policies plugin: schedule {} at {}: no phone can open apps ({} skipped)", s.getId(), slot, skipped);
            return;
        }
        JobRow job = new JobRow();
        job.setCustomerId(s.getCustomerId());
        job.setAction("open");
        String name = s.getAppName() != null && !s.getAppName().isEmpty() ? s.getAppName() : s.getPkg();
        job.setLabel(JobService.clip("Open " + name + " · " + slot.substring(11), 200));
        job.setCreatedAt(now);
        job.setDeadline(slotAt + s.getLateMinutes() * 60_000L);
        jobs.create(job, numbers, commands);
        log.info("Policies plugin: schedule {} fired at {}: {} phones ({} can't open apps)", s.getId(), slot, numbers.size(), skipped);
    }
}
