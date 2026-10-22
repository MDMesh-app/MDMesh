/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.service.EmailService;
import com.hmdm.util.ExecutorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Emails a device's managers when the device reports {@value #EVENT_TYPE}: too many wrong kiosk exit PINs were
 * entered, so the agent locked the exit on the device for a while (the device stays in kiosk mode).
 *
 * <p>The check-in calls {@link #onEvent} for every event it stores. That call never throws and never waits for the
 * mail server: the mail is sent from a background thread. Who gets it: {@link KioskAlertRecipients}. What it says:
 * {@link KioskLockoutMail}. How often: {@link KioskAlertLimits}. Without SMTP nothing happens.</p>
 */
@Singleton
public class KioskLockoutAlert {

    private static final Logger logger = LoggerFactory.getLogger(KioskLockoutAlert.class);

    /** The device event this alert is about. */
    public static final String EVENT_TYPE = "kioskPinLockout";

    /** Alerts waiting for the mail thread. More are refused, so a slow mail server can't pile up work. */
    private static final int MAX_QUEUED = 100;

    private final UserMapper userMapper;
    private final EmailService emailService;
    private final String baseUrl;
    private final Executor mailThread;
    private final KioskAlertLimits limits;
    private final LongSupplier clock;

    @Inject
    public KioskLockoutAlert(UserMapper userMapper, EmailService emailService, @Named("base.url") String baseUrl) {
        this(userMapper, emailService, baseUrl, newMailThread(), new KioskAlertLimits(System::currentTimeMillis),
                System::currentTimeMillis);
    }

    /** With an explicit mail thread, limits and clock (tests). */
    public KioskLockoutAlert(UserMapper userMapper, EmailService emailService, String baseUrl,
                             Executor mailThread, KioskAlertLimits limits, LongSupplier clock) {
        this.userMapper = userMapper;
        this.emailService = emailService;
        this.baseUrl = baseUrl;
        this.mailThread = mailThread;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * One daemon thread with a bounded queue. Registered with {@link ExecutorRegistry}, which shuts it down when the
     * web application stops; the SMTP connect and read timeouts in {@link EmailService} bound a send in progress.
     */
    private static Executor newMailThread() {
        return ExecutorRegistry.register(new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED),
                r -> {
                    Thread t = new Thread(r, "kiosk-lockout-mail");
                    t.setDaemon(true);
                    return t;
                }));
    }

    /**
     * Queues the alert mail when {@code type} is a lockout, SMTP is set up and the limits allow it. Never throws.
     *
     * @param device the checked-in device, as read from the database
     * @param detail the device's text for the event (untrusted)
     */
    public void onEvent(Device device, String type, String detail) {
        if (!EVENT_TYPE.equals(type) || device == null) {
            return;
        }
        try {
            if (!emailService.isConfigured()) {
                return;
            }
            if (!limits.tryAcquire(device)) {
                logger.info("Device {}: kiosk exit PIN locked again; alert held back by the hourly limit",
                        device.getNumber());
                return;
            }
            queue(device, clock.getAsLong(), detail);
        } catch (RuntimeException e) {
            logger.warn("Device {}: kiosk lockout alert failed", device.getNumber(), e);
        }
    }

    private void queue(Device device, long receivedAt, String detail) {
        try {
            mailThread.execute(() -> send(device, receivedAt, detail));
        } catch (RejectedExecutionException e) {
            // Nothing was sent, so the next lockout may try again.
            limits.release(device);
            logger.warn("Device {}: kiosk lockout alert dropped: the mail queue is full or the server is stopping",
                    device.getNumber());
        }
    }

    /** Runs on the mail thread. */
    private void send(Device device, long receivedAt, String detail) {
        try {
            List<String> to = KioskAlertRecipients.of(device, userMapper.findAll(device.getCustomerId()));
            if (to.isEmpty()) {
                logger.info("Device {}: kiosk exit PIN locked; no user with an email address can manage this device",
                        device.getNumber());
                return;
            }
            String subject = KioskLockoutMail.subject(device);
            String body = KioskLockoutMail.body(device, receivedAt, detail, baseUrl);
            for (String address : to) {
                sendOne(device, address, subject, body);
            }
        } catch (RuntimeException e) {
            logger.warn("Device {}: kiosk lockout alert failed", device.getNumber(), e);
        }
    }

    /** One recipient's failure must not stop the others. */
    private void sendOne(Device device, String address, String subject, String body) {
        try {
            if (!emailService.sendPlainTextEmail(address, subject, body)) {
                logger.warn("Device {}: kiosk lockout alert to {} not sent", device.getNumber(), address);
            }
        } catch (RuntimeException e) {
            logger.warn("Device {}: kiosk lockout alert to {} failed", device.getNumber(), address, e);
        }
    }
}
