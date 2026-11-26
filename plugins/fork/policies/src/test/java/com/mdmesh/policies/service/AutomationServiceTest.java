package com.mdmesh.policies.service;

import com.mdmesh.policies.persistence.JobDAO;
import com.mdmesh.policies.persistence.domain.AutomationSettings;
import com.mdmesh.policies.persistence.domain.FreshScan;
import org.junit.Before;
import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AutomationServiceTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private FakeAutomationMapper auto;
    private FakeJobMapper db;
    private AutomationService service;
    private JobService jobs;

    @Before
    public void setUp() {
        auto = new FakeAutomationMapper();
        db = new FakeJobMapper();
        JobDAO dao = new JobDAO(db);
        jobs = new JobService(dao, new CoreInstaller(type -> new Object(), "com.example.NoInstaller"));
        service = new AutomationService(auto, jobs, dao);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, IST).toInstant().toEpochMilli();
    }

    private AutomationSettings scanning(int start, int end) {
        return service.save(1, true, start, end, "Asia/Kolkata");
    }

    // ---- the window and the batch ----

    @Test
    public void aWindowCrossingMidnightBelongsToTheNightItStarted() {
        ZonedDateTime late = ZonedDateTime.of(2026, 11, 15, 23, 0, 0, 0, IST);
        ZonedDateTime early = ZonedDateTime.of(2026, 11, 16, 2, 0, 0, 0, IST);
        ZonedDateTime day = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, IST);
        assertEquals("2026-11-15", AutomationService.nightOf(late, 22, 4));
        assertEquals("2026-11-15", AutomationService.nightOf(early, 22, 4));
        assertNull(AutomationService.nightOf(day, 22, 4));
        assertEquals("2026-11-16", AutomationService.nightOf(early, 1, 5));
        assertNull(AutomationService.nightOf(late, 1, 5));
        assertEquals(6 * 60, AutomationService.windowMinutes(22, 4));
        assertEquals(24 * 60, AutomationService.windowMinutes(3, 3));
    }

    @Test
    public void theBatchFinishesTheSweepInTheFirstHalfOfTheWindow() {
        assertEquals(1, AutomationService.batchSize(41, 4 * 60));       // 41 phones over 120 minutes
        assertEquals(9, AutomationService.batchSize(1000, 4 * 60));     // 1000 phones: 9 a minute, done in ~112 minutes
        assertEquals(500, AutomationService.batchSize(1000000, 60));    // capped
        assertEquals(1, AutomationService.batchSize(0, 60));
    }

    // ---- the sweep ----

    @Test
    public void theSweepWalksThePhonesInIdOrderIntoOneJobAndStopsUntilTomorrow() {
        for (int id : new int[]{3, 7, 9, 12}) auto.phone(id);
        auto.recentlyScanned.add("P-9");                               // scanned today already: skipped
        AutomationSettings s = scanning(1, 2);                          // 60-minute window: 4 phones, batch 1

        assertEquals(0, service.sweep(s, at(2026, 11, 16, 0, 30)));     // before the window: nothing
        assertEquals(1, service.sweep(s, at(2026, 11, 16, 1, 0)));
        assertEquals(3, s.getSweepCursor());
        int job = s.getSweepJobId();
        assertEquals(1, service.sweep(s, at(2026, 11, 16, 1, 1)));
        assertEquals(7, s.getSweepCursor());
        assertEquals(1, service.sweep(s, at(2026, 11, 16, 1, 2)));      // 9 skipped: 12 next
        assertEquals(12, s.getSweepCursor());
        assertEquals(0, service.sweep(s, at(2026, 11, 16, 1, 3)));      // nothing above 12: done tonight
        assertEquals(AutomationService.SWEEP_DONE, s.getSweepCursor());
        assertEquals(0, service.sweep(s, at(2026, 11, 16, 1, 4)));

        // One job holds the night's three scans.
        assertEquals(Integer.valueOf(job), s.getSweepJobId());
        assertEquals(3, db.commandsOf(job).size());
        assertEquals("Nightly app scan · 2026-11-16", db.jobs.get(job).getLabel());

        // The next night starts again from the first phone (C-LOOK).
        auto.recentlyScanned.clear();
        assertEquals(1, service.sweep(s, at(2026, 11, 17, 1, 0)));
        assertEquals(3, s.getSweepCursor());
        assertTrue(s.getSweepJobId() != job);

        Map<String, Object> status = service.status(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> night = (Map<String, Object>) status.get("lastSweep");
        assertEquals("2026-11-17", night.get("night"));
        assertEquals(1, night.get("reached"));
        assertEquals(4, night.get("total"));
    }

    @Test
    public void cancellingTonightsSweepStopsItUntilTomorrow() {
        auto.phone(1);
        auto.phone(2);
        AutomationSettings s = scanning(1, 2);
        service.sweep(s, at(2026, 11, 16, 1, 0));
        jobs.cancel(1, s.getSweepJobId(), at(2026, 11, 16, 1, 0));
        assertEquals(0, service.sweep(s, at(2026, 11, 16, 1, 1)));
        assertEquals(AutomationService.SWEEP_DONE, s.getSweepCursor());
    }

    @Test
    public void newPhonesAreScannedAnyTime() {
        auto.phone(1);
        auto.phone(2);
        auto.everScanned.add("P-1");
        assertEquals(1, service.scanNewPhones(1, at(2026, 11, 16, 14, 0)));
        assertEquals("P-2", db.commands.values().iterator().next().deviceNumber);
    }

    @Test
    public void settingsAreChecked() {
        try {
            service.save(1, true, 25, 3, "UTC");
            throw new AssertionError("hour 25 accepted");
        } catch (JobService.Refused expected) {
            // refused
        }
        try {
            service.save(1, true, 1, 3, "Mars/Base");
            throw new AssertionError("unknown zone accepted");
        } catch (JobService.Refused expected) {
            // refused
        }
    }

    // ---- auto-remove ----

    private FreshScan fresh(String number, long at, String apps) {
        FreshScan f = new FreshScan();
        f.setId(Integer.parseInt(number.substring(2)));
        f.setNumber(number);
        f.setConfigurationId(4);
        f.setCustomerId(1);
        f.setScannedAt(at);
        f.setScanDetail("{\"apps\":" + apps + "}");
        return f;
    }

    @Test
    public void autoRemoveStartsFromNowSoOldScansAreNeverUsed() {
        auto.fresh.add(fresh("P-1", 100L, "[{\"pkg\":\"com.game\"}]"));
        auto.policies.add(FakeAutomationMapper.kv("4", "{\"apps\":{\"mode\":\"auto\"}}"));
        auto.allowed.add(FakeAutomationMapper.kv("4", "com.whatsapp"));
        assertEquals(0, service.autoRemove(1, 1000L));
        assertEquals(Long.valueOf(1000L), auto.getSettings(1).getAutoRemoveSince());
        assertTrue(db.jobs.isEmpty());
    }

    @Test
    public void aFreshScanOnAnAutoRemovePolicyQueuesItsExtraAppsInOneJob() {
        auto.saveAutoRemoveSince(1, 1000L);
        auto.fresh.add(fresh("P-1", 2000L, "[{\"pkg\":\"com.whatsapp\",\"label\":\"WhatsApp\"},"
                + "{\"pkg\":\"com.game\",\"label\":\"Game\"},{\"pkg\":\"com.android.chrome\",\"system\":true},"
                + "{\"pkg\":\"com.mdmesh.agent\"},{\"pkg\":\"com.kept\"},{\"pkg\":\"com.going\"}]"));
        auto.fresh.add(fresh("P-2", 2500L, "[{\"pkg\":\"com.whatsapp\"}]"));
        auto.fresh.add(fresh("P-3", 500L, "[{\"pkg\":\"com.old\"}]"));         // before the last check: ignored
        auto.policies.add(FakeAutomationMapper.kv("4",
                "{\"apps\":{\"mode\":\"auto\",\"notify\":true,\"exceptions\":[{\"pkg\":\"com.kept\",\"device\":\"P-1\"}]}}"));
        auto.allowed.add(FakeAutomationMapper.kv("4", "com.whatsapp"));
        auto.uninstalls.add(FakeAutomationMapper.kv("P-1", "{\"packageName\":\"com.going\"}"));

        assertEquals(1, service.autoRemove(1, 3000L));
        assertEquals(1, db.jobs.size());
        int job = db.jobs.keySet().iterator().next();
        assertEquals("remove", db.jobs.get(job).getAction());
        List<FakeJobMapper.Cmd> cmds = db.commandsOf(job);
        assertEquals(2, cmds.size());
        assertEquals("{\"packageName\":\"com.game\"}", cmds.get(0).payload);
        assertEquals("device.alert", cmds.get(1).type);
        assertTrue(cmds.get(1).payload.contains("Game is not allowed on this phone."));
        assertEquals(1, db.devicesOf(job).size());
        assertEquals(Long.valueOf(3000L), auto.getSettings(1).getAutoRemoveSince());

        // The same scan is never acted on twice.
        assertEquals(0, service.autoRemove(1, 4000L));
        assertEquals(1, db.jobs.size());
    }

    @Test
    public void aKioskPolicyWithNoAllowedAppIsLeftAlone() {
        auto.saveAutoRemoveSince(1, 1000L);
        auto.fresh.add(fresh("P-1", 2000L, "[{\"pkg\":\"com.game\"}]"));
        auto.policies.add(FakeAutomationMapper.kv("4", "{\"apps\":{\"mode\":\"auto\"}}"));
        assertEquals(0, service.autoRemove(1, 3000L));
        assertTrue(db.jobs.isEmpty());
    }

    @Test
    public void theRuleMatchesTheConsoles() {
        AutoRemoveRules.Rules report = AutoRemoveRules.parse("{\"apps\":{\"mode\":\"report\"}}");
        assertTrue(AutoRemoveRules.extras("{\"apps\":[{\"pkg\":\"com.game\"}]}", Collections.<String>emptySet(), report, "P-1",
                Collections.<String>emptySet()).isEmpty());
        AutoRemoveRules.Rules broken = AutoRemoveRules.parse("not json");
        assertEquals("report", broken.mode);
        assertTrue(broken.notify);
        AutoRemoveRules.Rules quiet = AutoRemoveRules.parse("{\"apps\":{\"mode\":\"auto\",\"notify\":false}}");
        assertEquals(false, quiet.notify);
        assertEquals("[\"App removed by your administrator\", \"A, B are not allowed on this phone.\"]",
                "[\"" + String.join("\", \"", AutoRemoveRules.alert(java.util.Arrays.asList("A", "B"))) + "\"]");
    }
}
