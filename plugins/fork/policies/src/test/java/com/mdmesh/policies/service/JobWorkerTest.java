package com.mdmesh.policies.service;

import com.mdmesh.policies.persistence.JobDAO;
import com.mdmesh.policies.rest.json.JobRequest;
import com.mdmesh.policies.rest.json.JobView;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JobWorkerTest {
    private FakeJobMapper db;
    private JobDAO dao;
    private JobService service;
    private FakeHub hub;

    @Before
    public void setUp() {
        db = new FakeJobMapper();
        for (int i = 1; i <= 12; i++) db.phone(i, "P-" + i, 4);
        dao = new JobDAO(db);
        service = new JobService(dao, new CoreInstaller(type -> new Object(), "com.example.NoInstaller"));
        hub = new FakeHub();
        FakeHub.INSTANCE = hub;
    }

    private JobView scan(int... ids) {
        JobRequest r = new JobRequest();
        r.action = "scan";
        r.deviceIds = new ArrayList<>();
        for (int id : ids) r.deviceIds.add(id);
        return service.create(1, 1, false, r, 100L);
    }

    @Test
    public void wakesAtMostTheRatePerTickOldestFirstAndOnlyOnlinePhones() {
        JobView v = scan(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        hub.online.add("P-1");
        hub.online.add("P-3");
        hub.online.add("P-7");
        JobWorker worker = new JobWorker(dao, new CoreWake(FakeHub.class.getName()), 5);

        assertEquals(5, worker.wakeNext(1000L));
        assertEquals(2, hub.woken.size());
        assertEquals("P-1:commands", hub.woken.get(0));
        assertEquals(5, worker.wakeNext(2000L));
        assertEquals(2, worker.wakeNext(3000L));
        assertEquals(0, worker.wakeNext(4000L));
        assertEquals(3, hub.woken.size());

        JobView after = service.recent(1, 5000L).get(0);
        assertEquals(after.id, v.id);
        assertEquals(Integer.valueOf(3), after.wake.get("woken"));
        assertEquals(Integer.valueOf(9), after.wake.get("offline"));
        assertEquals("waiting", after.phase);
    }

    @Test
    public void aPhoneInTwoJobsIsWokenOncePerTick() {
        scan(1);
        scan(1);
        hub.online.add("P-1");
        JobWorker worker = new JobWorker(dao, new CoreWake(FakeHub.class.getName()), 5);
        worker.wakeNext(1000L);
        assertEquals(1, hub.woken.size());
    }

    @Test
    public void withoutCoresWakeServiceEveryPhoneWaitsForItsCheckIn() {
        scan(1, 2);
        hub.online.add("P-1");
        JobWorker worker = new JobWorker(dao, new CoreWake("com.example.NoHub"), 5);
        worker.wakeNext(1000L);
        assertTrue(hub.woken.isEmpty());
        for (FakeJobMapper.Device d : db.devices.values()) assertEquals("offline", d.wake);
    }

    @Test
    public void anExpiredNeverDeliveredCommandIsSentAgainAtMostThreeTimes() {
        JobView v = scan(1, 2);
        JobWorker worker = new JobWorker(dao, new CoreWake(FakeHub.class.getName()), 5);
        worker.wakeNext(1000L);
        List<FakeJobMapper.Cmd> cmds = db.commandsOf(v.id);
        cmds.get(0).status = "expired";              // never reached P-1
        cmds.get(1).status = "expired";              // reached P-2 but never answered: not sent again
        cmds.get(1).deliveredAt = 500L;

        assertEquals(1, worker.retryExpired(2000L));
        FakeJobMapper.Cmd again = db.commandsOf(v.id).get(0);
        assertEquals("pending", again.status);
        assertEquals("P-1", again.deviceNumber);
        assertEquals("queued", db.devicesOf(v.id).get(0).wake);

        again.status = "expired";
        assertEquals(1, worker.retryExpired(3000L));
        db.commandsOf(v.id).get(0).status = "expired";
        assertEquals(0, worker.retryExpired(4000L));   // third attempt was the last
    }

    @Test
    public void aCancelledJobIsNeverRetried() {
        JobView v = scan(1);
        db.commandsOf(v.id).get(0).status = "expired";
        service.cancel(1, v.id, 150L);
        JobWorker worker = new JobWorker(dao, new CoreWake(FakeHub.class.getName()), 5);
        assertEquals(0, worker.retryExpired(2000L));
    }

    @Test
    public void aFailingStepNeverStopsTheWorker() {
        JobWorker worker = new JobWorker(new JobDAO(null), new CoreWake(FakeHub.class.getName()), 5);
        worker.tick(1000L);   // every step fails on the missing mapper; the tick returns normally
    }
}
