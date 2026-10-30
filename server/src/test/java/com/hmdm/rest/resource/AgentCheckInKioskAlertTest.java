/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentCheckInRequest;
import com.hmdm.rest.json.agent.AgentTelemetryEvent;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.rest.resource.support.InstallHashes;
import com.hmdm.rest.resource.support.KioskAlertLimits;
import com.hmdm.rest.resource.support.KioskLockoutAlert;
import com.hmdm.rest.resource.support.RolloutRequeuer;
import com.hmdm.service.EmailService;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import com.hmdm.util.CryptoUtil;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;

/** The check-in hands lockout events to the alert, and nothing the alert does can fail or slow the check-in. */
public class AgentCheckInKioskAlertTest {

    private static final String SECRET = "s3cret";
    private static final String DEVICE = "1234";

    private static final class FakeUnsecureDAO extends UnsecureDAO {
        FakeUnsecureDAO() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, "unused", 0, null);
        }
        @Override public Device getDeviceByNumber(String number) {
            if (!DEVICE.equals(number)) return null;
            Device d = new Device();
            d.setId(1);
            d.setNumber(DEVICE);
            d.setCustomerId(1);
            d.setGroups(new ArrayList<>());
            return d;
        }
    }

    private static final class FakeCommandDAO extends AgentCommandDAO {
        final List<String> events = new ArrayList<>();
        FakeCommandDAO() { super(null, null, null, null); }
        @Override public String getDeviceSecretHash(String n) { return CryptoUtil.getSHA256String(SECRET); }
        @Override public void touchLastUpdate(String n) { }
        @Override public void expireStale(String n, long a, long b) { }
        @Override public String getDeviceCapabilities(String n) { return null; }
        @Override public List<AgentCommand> listPending(String n) { return Collections.emptyList(); }
        @Override public void insertEvent(String n, String type, Long ts, String detail) { events.add(type); }
    }

    private static final class FakeEmail extends EmailService {
        final List<String> to = new ArrayList<>();
        RuntimeException failWith;
        FakeEmail() {
            super("", 25, false, false, "", "", "", "", "mdm@localhost", "", "", "", "");
        }
        @Override public boolean isConfigured() { return true; }
        @Override public boolean sendPlainTextEmail(String to, String subj, String body) {
            if (failWith != null) throw failWith;
            this.to.add(to);
            return true;
        }
    }

    private FakeCommandDAO commands;
    private FakeEmail email;
    private final List<Runnable> queued = new ArrayList<>();

    private AgentResource resource(Executor executor) {
        FakeMapper<UserMapper> users = new FakeMapper<>(UserMapper.class).answer("findAll", a -> {
            User u = Fakes.user(9, 1, "edit_devices");
            u.setEmail("admin@example.com");
            return Collections.singletonList(u);
        });
        KioskLockoutAlert alert = new KioskLockoutAlert(users.mapper, email, "https://mdm.example.com",
                executor, new KioskAlertLimits(System::currentTimeMillis), System::currentTimeMillis);
        return new AgentResource(new FakeUnsecureDAO(), null, commands,
                new ConfigAppInstaller(null, null, null, null),
                new ConfigReconciler(null, null, Fakes.noExtensionPoints()) {
                    @Override public boolean reconcile(Device d, Set<String> t, String rev, String av, String cav,
                                                       long now, long receivedAt) { return false; }
                },
                new RolloutRequeuer(null) { @Override public boolean requeue(Device d, long now) { return false; } },
                new InstallHashes(null, null), alert);
    }

    @Before
    public void setUp() {
        commands = new FakeCommandDAO();
        email = new FakeEmail();
    }

    private static AgentTelemetryEvent event(String type, String detail) {
        AgentTelemetryEvent e = new AgentTelemetryEvent();
        e.setType(type);
        e.setTs(1792591392000L);
        e.setDetail(detail);
        return e;
    }

    private Response checkin(AgentResource resource, AgentTelemetryEvent... events) {
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setDeviceId(DEVICE);
        req.setEvents(Arrays.asList(events));
        return resource.checkin("Bearer " + SECRET, null, req);
    }

    @Test
    public void aLockoutEventIsStoredAndMailedAfterTheResponse() {
        Response r = checkin(resource(queued::add),
                event("boot", null), event(KioskLockoutAlert.EVENT_TYPE, "3 wrong exit PINs; exit locked for 30 s"));

        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(Arrays.asList("boot", KioskLockoutAlert.EVENT_TYPE), commands.events);
        assertEquals("the mail is not sent on the check-in thread", 0, email.to.size());
        assertEquals(1, queued.size());
        queued.get(0).run();
        assertEquals(Collections.singletonList("admin@example.com"), email.to);
    }

    @Test
    public void otherEventsDoNotMail() {
        checkin(resource(Runnable::run), event("kioskCrashLoop", "kiosk app closed repeatedly"),
                event("boot", null), event("lowBattery", "12%"));
        assertEquals(3, commands.events.size());
        assertEquals(0, email.to.size());
    }

    @Test
    public void aFailingMailServerDoesNotFailTheCheckIn() {
        email.failWith = new IllegalStateException("smtp down");
        // Even run inline, a mail failure stays inside the alert.
        Response r = checkin(resource(Runnable::run), event(KioskLockoutAlert.EVENT_TYPE, "x"), event("boot", null));
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(2, commands.events.size());
    }

    @Test
    public void aFullMailQueueDoesNotFailTheCheckIn() {
        Response r = checkin(resource(job -> { throw new java.util.concurrent.RejectedExecutionException("full"); }),
                event(KioskLockoutAlert.EVENT_TYPE, "x"), event("boot", null));
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(2, commands.events.size());
    }
}
