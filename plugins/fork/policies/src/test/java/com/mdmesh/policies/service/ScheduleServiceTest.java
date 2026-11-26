package com.mdmesh.policies.service;

import com.mdmesh.policies.persistence.JobDAO;
import com.mdmesh.policies.persistence.domain.JobRow;
import org.junit.Before;
import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ScheduleServiceTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String CAN_OPEN = "{\"device\":[\"configApply\"],\"appManagement\":[\"silentInstall\",\"launch\"]}";
    private static final String CANT_OPEN = "{\"device\":[\"configApply\"],\"appManagement\":[\"silentInstall\"]}";

    private FakeJobMapper db;
    private FakeScheduleMapper sched;
    private ScheduleService service;

    @Before
    public void setUp() {
        db = new FakeJobMapper();
        sched = new FakeScheduleMapper();
        service = new ScheduleService(sched, new JobDAO(db));
    }

    private static long at(int hour, int minute, int second) {
        return ZonedDateTime.of(2026, 11, 26, hour, minute, second, 0, IST).toInstant().toEpochMilli();
    }

    private static ScheduleService.Input input(String target, String... times) {
        ScheduleService.Input in = new ScheduleService.Input();
        in.pkg = "com.whatsapp";
        in.appName = "WhatsApp";
        in.target = target;
        in.days = 127;
        in.times = new ArrayList<>(Arrays.asList(times));
        in.timeZone = "Asia/Kolkata";
        in.lateMinutes = 15;
        return in;
    }

    private void refused(String expected, ScheduleService.Input in) {
        try {
            service.save(1, 4, null, in, 0L);
            fail("expected: " + expected);
        } catch (JobService.Refused e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expected));
        }
    }

    private List<String> openedOn() {
        List<String> out = new ArrayList<>();
        for (JobRow j : db.jobs.values()) for (FakeJobMapper.Cmd c : db.commandsOf(j.getId())) {
            assertEquals("app.launch", c.type);
            assertEquals("app.launch", c.capability);
            assertEquals("{\"packageName\":\"com.whatsapp\"}", c.payload);
            out.add(c.deviceNumber);
        }
        return out;
    }

    @Test
    public void opensTheAppOnEveryPhoneOfThePolicyThatCanOpenApps() {
        sched.phone(4, 1, "P-1", CAN_OPEN, "OPPO", null);
        sched.phone(4, 2, "P-2", CANT_OPEN, "vivo", null);        // can't open apps: skipped, never queued
        sched.phone(4, 3, "P-3", CAN_OPEN, "vivo", null);
        service.save(1, 4, null, input("policy", "09:00"), at(8, 0, 0));
        assertEquals(1, service.tick(at(9, 0, 20)));
        assertEquals(0, service.tick(at(9, 0, 35)));                   // the same slot never fires twice
        assertEquals(Arrays.asList("P-1", "P-3"), openedOn());
        JobRow job = db.jobs.values().iterator().next();
        assertEquals("open", job.getAction());
        assertEquals("Open WhatsApp · 09:00", job.getLabel());
        assertEquals(Long.valueOf(at(9, 15, 0)), job.getDeadline());   // offline phones may get it until 09:15
    }

    @Test
    public void aGroupReachesItsPhonesByBrandAndThosePickedIntoIt() {
        sched.group(7, "Oppo family", "oppo", "realme");
        sched.group(8, "Vivo family", "vivo");
        sched.phone(4, 1, "OPPO-1", CAN_OPEN, "OPPO", null);
        sched.phone(4, 2, "VIVO-1", CAN_OPEN, "vivo", null);
        sched.phone(4, 3, "VIVO-PICKED", CAN_OPEN, "vivo", null).setPickedGroupId(7);   // picked into Oppo family
        sched.phone(4, 4, "OPPO-PICKED", CAN_OPEN, "OPPO", null).setPickedGroupId(8);   // picked out of it
        sched.phone(4, 5, "NAMED", CAN_OPEN, null, "Realme 11 Pro front desk");          // brand from its name
        sched.phone(4, 6, "SET", CAN_OPEN, "vivo", null).setSetBrand("realme");          // brand set by hand
        ScheduleService.Input in = input("group", "09:00");
        in.groupId = 7;
        service.save(1, 4, null, in, at(8, 0, 0));
        service.tick(at(9, 0, 0));
        assertEquals(Arrays.asList("OPPO-1", "VIVO-PICKED", "NAMED", "SET"), openedOn());
    }

    @Test
    public void pickedPhonesOnly() {
        sched.phone(4, 1, "P-1", CAN_OPEN, "OPPO", null);
        sched.phone(4, 2, "P-2", CAN_OPEN, "OPPO", null);
        ScheduleService.Input in = input("phones", "09:00");
        in.devices = Arrays.asList(2, 99);                          // 99 isn't on the policy: left out
        service.save(1, 4, null, in, at(8, 0, 0));
        service.tick(at(9, 0, 0));
        assertEquals(Collections.singletonList("P-2"), openedOn());
    }

    @Test
    public void aSlotMissedByMoreThanTheLateWindowNeverFires() {
        sched.phone(4, 1, "P-1", CAN_OPEN, "OPPO", null);
        service.save(1, 4, null, input("policy", "09:00"), at(8, 0, 0));
        assertEquals(0, service.tick(at(9, 15, 0)));                   // the server was down from 09:00 to 09:15
        assertTrue(db.jobs.isEmpty());
    }

    @Test
    public void undeliveredOpensExpireAtTheDeadline() {
        sched.phone(4, 1, "P-1", CAN_OPEN, "OPPO", null);
        service.save(1, 4, null, input("policy", "09:00"), at(8, 0, 0));
        service.tick(at(9, 0, 0));
        new JobDAO(db).expireLate(at(9, 14, 0));
        assertEquals("pending", db.commandsOf(db.jobs.keySet().iterator().next()).get(0).status);
        new JobDAO(db).expireLate(at(9, 15, 1));
        FakeJobMapper.Cmd c = db.commandsOf(db.jobs.keySet().iterator().next()).get(0);
        assertEquals("expired", c.status);
        assertEquals("Too late for its schedule: skipped", c.detail);
    }

    @Test
    public void validatesWhatItSaves() {
        ScheduleService.Input in = input("policy", "09:00");
        in.pkg = "not a package";
        refused("Pick the app to open", in);
        refused("Pick the group", input("group", "09:00"));
        refused("Pick at least one phone", input("phones", "09:00"));
        refused("Choose who it opens on", input("everyone", "09:00"));
        refused("Times are HH:MM", input("policy", "9am"));
        in = input("policy", "09:00");
        in.lateMinutes = 0;
        refused("1 to 240 minutes late", in);
        in = input("group", "09:00");
        in.groupId = 42;
        refused("No such group", in);
        try {
            service.save(1, 9, null, input("policy", "09:00"), 0L);
            fail("expected: No such policy");
        } catch (JobService.Refused e) {
            assertEquals("No such policy.", e.getMessage());
        }
    }

    @Test
    public void listsWhenEachScheduleNextRuns() {
        service.save(1, 4, null, input("policy", "09:00", "13:30"), at(8, 0, 0));
        List<Map<String, Object>> list = service.list(1, 4, at(10, 0, 0));
        assertEquals(1, list.size());
        assertEquals(at(13, 30, 0), list.get(0).get("nextRun"));
        assertEquals(Arrays.asList("09:00", "13:30"), list.get(0).get("times"));
        assertNull(list.get(0).get("groupId"));
    }

    @Test
    public void aBrokenTelemetryRowIsReadAsNoBrandNotAnError() {
        assertNull(ScheduleService.reported("{not json")[0]);
        assertEquals("OPPO", ScheduleService.reported("{\"hardware\":{\"brand\":\"OPPO\"}}")[0]);
    }
}
