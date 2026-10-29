/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.service.EmailService;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The entry point: which events mail, when, and that nothing it does can reach the check-in. */
public class KioskLockoutAlertTest {

    private static final int CUSTOMER = 4;
    private static final long HOUR = 60L * 60L * 1000L;
    /** 2026-10-21 14:03:12 UTC */
    private static final long SERVER_NOW = 1792591392000L;

    /** Records every mail; optionally throws instead of sending. */
    static class FakeEmail extends EmailService {
        final List<String[]> sent = new ArrayList<>();
        boolean configured = true;
        RuntimeException failWith;

        FakeEmail() {
            super("", 25, false, false, "", "", "", "", "mdm@localhost", "", "", "", "");
        }

        @Override public boolean isConfigured() { return configured; }

        @Override public boolean sendPlainTextEmail(String to, String subj, String body) {
            if (failWith != null) throw failWith;
            sent.add(new String[]{to, subj, body});
            return true;
        }
    }

    private FakeEmail email;
    private FakeMapper<UserMapper> users;
    private final List<Runnable> queued = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(SERVER_NOW);

    private static Device device(String number) {
        Device d = new Device();
        d.setNumber(number);
        d.setCustomerId(CUSTOMER);
        d.setGroups(new ArrayList<>());
        return d;
    }

    private static User admin(int id, String mail) {
        User u = Fakes.user(id, CUSTOMER, "edit_devices");
        u.setEmail(mail);
        return u;
    }

    private KioskLockoutAlert alert(EmailService mail, Executor executor) {
        return new KioskLockoutAlert(users.mapper, mail, "https://mdm.example.com", executor,
                new KioskAlertLimits(now::get), now::get);
    }

    private KioskLockoutAlert alert() {
        return alert(email, queued::add);
    }

    @Before
    public void setUp() {
        email = new FakeEmail();
        users = new FakeMapper<>(UserMapper.class)
                .answer("findAll", a -> Collections.singletonList(admin(1, "admin@example.com")));
    }

    private void runQueued() {
        List<Runnable> jobs = new ArrayList<>(queued);
        queued.clear();
        jobs.forEach(Runnable::run);
    }

    @Test
    public void aLockoutIsMailedInTheBackgroundToTheDeviceCustomersManagers() {
        users.answer("findAll", a -> Arrays.asList(admin(1, "a@example.com"), admin(2, "b@example.com")));
        alert().onEvent(device("1234"), KioskLockoutAlert.EVENT_TYPE, "3 wrong exit PINs");
        assertEquals("nothing is sent on the check-in thread", 0, email.sent.size());
        runQueued();

        assertEquals(CUSTOMER, users.callsTo("findAll").get(0).args[0]);
        assertEquals(2, email.sent.size());
        assertEquals("a@example.com", email.sent.get(0)[0]);
        assertEquals("b@example.com", email.sent.get(1)[0]);
        assertEquals("MDMesh: kiosk exit PIN locked on device 1234", email.sent.get(0)[1]);
        assertTrue(email.sent.get(0)[2], email.sent.get(0)[2].contains("2026-10-21 14:03:12 UTC"));
    }

    @Test
    public void theServerClockIsReadWhenTheEventArrivesNotWhenTheMailIsSent() {
        alert().onEvent(device("1234"), KioskLockoutAlert.EVENT_TYPE, "x");
        now.addAndGet(5 * 60_000L);
        runQueued();
        assertTrue(email.sent.get(0)[2], email.sent.get(0)[2].contains("14:03:12 UTC"));
    }

    @Test
    public void atMostOneMailPerDevicePerHour() {
        KioskLockoutAlert alert = alert();
        for (int i = 0; i < 5; i++) {
            alert.onEvent(device("1234"), KioskLockoutAlert.EVENT_TYPE, "x");
        }
        alert.onEvent(device("5678"), KioskLockoutAlert.EVENT_TYPE, "x");
        runQueued();
        assertEquals(2, email.sent.size());

        now.addAndGet(HOUR);
        alert.onEvent(device("1234"), KioskLockoutAlert.EVENT_TYPE, "x");
        runQueued();
        assertEquals(3, email.sent.size());
    }

    @Test
    public void atMostTenAlertsPerCustomerPerHour() {
        KioskLockoutAlert alert = alert();
        for (int i = 0; i < 25; i++) {
            alert.onEvent(device("dev-" + i), KioskLockoutAlert.EVENT_TYPE, "x");
        }
        runQueued();
        assertEquals(KioskAlertLimits.PER_CUSTOMER_PER_HOUR, email.sent.size());
    }

    @Test
    public void otherEventTypesNeverSend() {
        KioskLockoutAlert alert = alert();
        for (String type : new String[]{"kioskCrashLoop", "boot", "commandResult", "KIOSKPINLOCKOUT", "", null}) {
            alert.onEvent(device("1234"), type, "x");
        }
        assertTrue(queued.isEmpty());
        assertTrue(users.calls.isEmpty());
    }

    @Test
    public void withoutSmtpNothingIsQueuedAndTheHourIsNotUsedUp() {
        KioskLockoutAlert alert = alert();
        email.configured = false;
        alert.onEvent(device("1234"), KioskLockoutAlert.EVENT_TYPE, "x");
        assertTrue(queued.isEmpty());

        email.configured = true;
        alert.onEvent(device("1234"), KioskLockoutAlert.EVENT_TYPE, "x");
        runQueued();
        assertEquals(1, email.sent.size());
    }

    @Test
    public void aMailTheQueueRefusedDoesNotUseUpTheHour() {
        boolean[] full = {true};
        KioskLockoutAlert alert = alert(email, job -> {
            if (full[0]) throw new RejectedExecutionException("full");
            queued.add(job);
        });
        alert.onEvent(device("1234"), KioskLockoutAlert.EVENT_TYPE, "x"); // must not throw
        full[0] = false;
        alert.onEvent(device("1234"), KioskLockoutAlert.EVENT_TYPE, "x");
        runQueued();
        assertEquals(1, email.sent.size());
    }

    @Test
    public void aFailingMailServerOrLookupNeverEscapes() {
        KioskLockoutAlert alert = alert();
        email.failWith = new IllegalStateException("smtp down");
        alert.onEvent(device("1234"), KioskLockoutAlert.EVENT_TYPE, "x");
        runQueued(); // must not throw

        users.answer("findAll", a -> { throw new IllegalStateException("db down"); });
        alert.onEvent(device("5678"), KioskLockoutAlert.EVENT_TYPE, "x");
        runQueued(); // must not throw

        alert.onEvent(null, KioskLockoutAlert.EVENT_TYPE, "x"); // must not throw
    }

    @Test
    public void oneRecipientFailingDoesNotStopTheOthers() {
        users.answer("findAll", a -> Arrays.asList(admin(1, "a@example.com"), admin(2, "b@example.com")));
        FakeEmail flaky = new FakeEmail() {
            @Override public boolean sendPlainTextEmail(String to, String subj, String body) {
                if (to.startsWith("a@")) throw new IllegalStateException("rejected");
                return super.sendPlainTextEmail(to, subj, body);
            }
        };
        alert(flaky, queued::add).onEvent(device("1234"), KioskLockoutAlert.EVENT_TYPE, "x");
        runQueued();
        assertEquals(1, flaky.sent.size());
        assertEquals("b@example.com", flaky.sent.get(0)[0]);
    }
}
