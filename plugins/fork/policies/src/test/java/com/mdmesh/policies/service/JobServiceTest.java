package com.mdmesh.policies.service;

import com.mdmesh.policies.persistence.JobDAO;
import com.mdmesh.policies.rest.json.JobRequest;
import com.mdmesh.policies.rest.json.JobView;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class JobServiceTest {
    private FakeJobMapper db;
    private JobService service;

    @Before
    public void setUp() {
        db = new FakeJobMapper();
        db.phone(11, "P-1", 4);
        db.phone(12, "P-2", 4);
        db.phone(13, "P-3", 4);
        // An installer that can't be reached, as on a server whose core renamed it.
        CoreInstaller noInstaller = new CoreInstaller(type -> new Object(), "com.example.NoInstaller");
        service = new JobService(new JobDAO(db), noInstaller);
    }

    private static JobRequest request(String action, Integer... ids) {
        JobRequest r = new JobRequest();
        r.action = action;
        r.label = "Test";
        r.deviceIds = Arrays.asList(ids);
        return r;
    }

    private void refused(JobRequest r, String expected) {
        refused(service, r, expected);
    }

    private void refused(JobService s, JobRequest r, String expected) {
        try {
            s.create(1, 1, false, r, 100L);
            fail("expected a refusal");
        } catch (JobService.Refused e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expected));
        }
    }

    @Test
    public void aScanJobQueuesOneScanPerPhoneAndQueuesEveryPhoneForAWake() {
        JobView v = service.create(1, 1, false, request("scan", 11, 12, 13, 12), 100L);
        assertEquals(3, v.phones);
        assertEquals("sending", v.phase);
        assertEquals(Integer.valueOf(3), v.wake.get("queued"));
        assertEquals(Integer.valueOf(3), v.commands.get("pending"));
        for (FakeJobMapper.Cmd c : db.commandsOf(v.id)) {
            assertEquals("apps.scan", c.type);
            assertNull(c.capability);
        }
    }

    @Test
    public void onePhoneTheUserMayNotManageRefusesTheWholeJob() {
        refused(request("scan", 11, 99), "may not manage");
        assertTrue(db.jobs.isEmpty());
        assertTrue(db.commands.isEmpty());
    }

    @Test
    public void onlyAllowlistedActionsAreSentWithTheServersCapability() {
        JobRequest wipe = request("command", 11);
        wipe.command = new JobRequest.Command();
        wipe.command.type = "device.wipe";
        refused(wipe, "can't be sent");
        JobRequest config = request("command", 11);
        config.command = new JobRequest.Command();
        config.command.type = "config.apply";
        refused(config, "can't be sent");

        JobRequest lock = request("command", 11, 12);
        lock.command = new JobRequest.Command();
        lock.command.type = "device.lock";
        JobView v = service.create(1, 1, false, lock, 100L);
        List<FakeJobMapper.Cmd> cmds = db.commandsOf(v.id);
        assertEquals(2, cmds.size());
        assertEquals("device.lock", cmds.get(0).capability);
    }

    @Test
    public void aRemoveJobUninstallsEachPhonesAppsAndAlertsItsUser() {
        JobRequest r = new JobRequest();
        r.action = "remove";
        JobRequest.Removal one = new JobRequest.Removal();
        one.deviceId = 11;
        one.packages = Arrays.asList("com.truecaller", "com.truecaller", "com.whatsapp");
        one.alertTitle = "App removed";
        one.alertBody = "Truecaller and WhatsApp are not allowed.";
        JobRequest.Removal two = new JobRequest.Removal();
        two.deviceId = 12;
        two.packages = Collections.singletonList("com.game");
        r.removals = Arrays.asList(one, two);
        JobView v = service.create(1, 1, false, r, 100L);
        assertEquals(2, v.phones);
        List<FakeJobMapper.Cmd> cmds = db.commandsOf(v.id);
        assertEquals(4, cmds.size());
        assertEquals("{\"packageName\":\"com.truecaller\"}", cmds.get(0).payload);
        assertEquals("device.alert", cmds.get(2).type);
        assertEquals("device.alert", cmds.get(2).capability);
        assertTrue(cmds.get(2).payload.contains("\"body\":\"Truecaller and WhatsApp are not allowed.\""));
        assertEquals("P-2", cmds.get(3).deviceNumber);

        JobRequest bad = new JobRequest();
        bad.action = "remove";
        JobRequest.Removal x = new JobRequest.Removal();
        x.deviceId = 11;
        x.packages = Collections.singletonList("not a package; DROP TABLE");
        bad.removals = Collections.singletonList(x);
        refused(bad, "Not a package name");
    }

    @Test
    public void wipedPhonesAreLeftOutAsCoreDoes() {
        db.phone(14, "P-4", 4, null, 1000L);
        JobView v = service.create(1, 1, false, request("scan", 11, 14), 100L);
        assertEquals(1, v.phones);
        assertEquals(1, db.commandsOf(v.id).size());
        assertEquals("P-1", db.commandsOf(v.id).get(0).deviceNumber);
        refused(request("scan", 14), "These phones were wiped");
    }

    @Test
    public void aRemoveJobWithoutRemovalsIsRefusedNotACrash() {
        JobRequest r = new JobRequest();
        r.action = "remove";
        r.removals = null;
        refused(r, "No phones given");
    }

    @Test
    public void installIsRefusedWhenCoresInstallerIsMissing() {
        refused(request("install", 11), "isn't available");
    }

    @Test
    public void aReapplyJobOnlyWakes() {
        JobView v = service.create(1, 1, false, request("reapply", 11, 12), 100L);
        assertTrue(v.commands.isEmpty());
        assertEquals("sending", v.phase);
    }

    @Test
    public void cancellingStopsWakesAndExpiresOnlyUndeliveredCommands() {
        JobView v = service.create(1, 1, false, request("scan", 11, 12, 13), 100L);
        db.devicesOf(v.id).get(0).wake = "woken";
        db.commandsOf(v.id).get(0).status = "delivered";
        db.commandsOf(v.id).get(0).deliveredAt = 150L;
        JobView after = service.cancel(1, v.id, 200L);
        assertTrue(after.cancelled);
        assertEquals("cancelled", after.phase);
        assertEquals(Integer.valueOf(2), after.wake.get("cancelled"));
        assertEquals(Integer.valueOf(1), after.commands.get("delivered"));
        assertEquals(Integer.valueOf(2), after.commands.get("expired"));
        try {
            service.cancel(2, v.id, 200L);
            fail("another customer's job");
        } catch (JobService.Refused expected) {
            // refused
        }
    }

    @Test
    public void thePhaseSaysWhatIsLeft() {
        JobView v = new JobView();
        v.wake.put("woken", 2);
        v.commands.put("pending", 2);
        assertEquals("running", JobService.phase(v));
        v.wake.put("offline", 1);
        assertEquals("waiting", JobService.phase(v));
        v.commands.clear();
        v.commands.put("done", 3);
        assertEquals("done", JobService.phase(v));
        v.commands.put("delivered", 1);
        assertEquals("running", JobService.phase(v));
        assertFalse(v.cancelled);
    }
}
