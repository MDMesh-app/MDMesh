package com.mdmesh.policies.service;

import com.hmdm.util.ExecutorRegistry;
import com.mdmesh.policies.persistence.JobDAO;
import com.mdmesh.policies.persistence.domain.QueuedWake;
import com.mdmesh.policies.persistence.domain.RetryRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The one worker behind every bulk job: it wakes job phones at a fixed rate, oldest first (a rate-limited FIFO), so a
 * job of thousands of phones turns into a steady trickle of check-ins instead of a burst.
 *
 * <ul>
 *   <li>Each second: the next {@code wakesPerSecond} queued phones. An online phone is woken; an offline one is marked
 *   offline, as its commands already wait in the database for its next check-in (a wake can't reach it anyway).</li>
 *   <li>Each minute: commands that expired without ever reaching their phone (core expires them after 60 minutes) are
 *   queued again, at most {@link #MAX_ATTEMPTS} times in all, and their phones queued for a wake. A command a phone
 *   received is never sent twice.</li>
 *   <li>Every 15 seconds: due app schedules fire ({@link ScheduleService#tick}); every 5 seconds, scheduled commands
 *   past their deadline expire, so an app never opens late.</li>
 *   <li>Each minute: the automation ({@link AutomationService}): nightly sweep, new phones, auto-remove.</li>
 *   <li>Each hour: jobs older than 30 days are deleted (their commands stay in core's history).</li>
 * </ul>
 *
 * <p>Its state is the database, so a server restart carries on where it stopped. Every step catches its own errors:
 * a failing step is retried on the next tick and never stops the worker.</p>
 */
@Singleton
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    static final int MAX_ATTEMPTS = 3;
    static final long RETRY_WINDOW_MS = 24L * 60 * 60 * 1000;
    static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000;

    /** Phones woken per second: {@code -Dmdmesh.policies.wakesPerSecond} (1 to 100), default 5. */
    static int wakesPerSecond() {
        return Math.max(1, Math.min(100, Integer.getInteger("mdmesh.policies.wakesPerSecond", 5)));
    }

    private final JobDAO dao;
    private final CoreWake core;
    /** Nightly sweep, new phones, auto-remove: once a minute. Null in tests of the wake part. */
    private final AutomationService automation;
    /** App schedules, every 15 seconds. Null in tests of the wake part. */
    private final ScheduleService schedules;
    private final int rate;
    private long lastRetry;
    private long lastAutomation;
    private long lastLate;
    private long lastSchedules;
    private long lastPurge;
    private long lastWarn;
    private ScheduledExecutorService executor;

    @Inject
    public JobWorker(JobDAO dao, CoreWake core, AutomationService automation, ScheduleService schedules) {
        this(dao, core, automation, schedules, wakesPerSecond());
    }

    JobWorker(JobDAO dao, CoreWake core, int rate) {
        this(dao, core, null, null, rate);
    }

    JobWorker(JobDAO dao, CoreWake core, AutomationService automation, ScheduleService schedules, int rate) {
        this.dao = dao;
        this.core = core;
        this.automation = automation;
        this.schedules = schedules;
        this.rate = rate;
    }

    public synchronized void start() {
        if (executor != null) return;
        executor = ExecutorRegistry.register(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "policies-jobs");
            t.setDaemon(true);
            return t;
        }));
        executor.scheduleWithFixedDelay(() -> tick(System.currentTimeMillis()), 2, 1, TimeUnit.SECONDS);
        log.info("Policies plugin: job worker started, {} wakes per second", rate);
    }

    /** One pass: wakes; retries once a minute; purge once an hour. Never throws. */
    void tick(long now) {
        try {
            wakeNext(now);
        } catch (Exception e) {
            warn("waking job phones", e, now);
        }
        if (now - lastRetry >= 60_000L) {
            lastRetry = now;
            try {
                retryExpired(now);
            } catch (Exception e) {
                warn("retrying expired job commands", e, now);
            }
        }
        if (now - lastLate >= 5_000L) {
            lastLate = now;
            try {
                dao.expireLate(now);
            } catch (Exception e) {
                warn("expiring late scheduled commands", e, now);
            }
        }
        if (schedules != null && now - lastSchedules >= 15_000L) {
            lastSchedules = now;
            try {
                schedules.tick(now);
            } catch (Exception e) {
                warn("firing app schedules", e, now);
            }
        }
        if (automation != null && now - lastAutomation >= 60_000L) {
            lastAutomation = now;
            try {
                automation.tick(now);
            } catch (Exception e) {
                warn("running the automation", e, now);
            }
        }
        if (now - lastPurge >= 60L * 60 * 1000) {
            lastPurge = now;
            try {
                int n = dao.purge(now - KEEP_MS);
                if (n > 0) log.info("Policies plugin: deleted {} jobs older than 30 days", n);
            } catch (Exception e) {
                warn("deleting old jobs", e, now);
            }
        }
    }

    /** The next queued phones, oldest first. A phone queued twice in this batch is woken once. */
    int wakeNext(long now) {
        List<QueuedWake> next = dao.nextQueued(rate);
        if (next.isEmpty()) return 0;
        List<Integer> woken = new ArrayList<>();
        List<Integer> offline = new ArrayList<>();
        Set<String> done = new HashSet<>();
        boolean reachable = core.available();
        for (QueuedWake q : next) {
            if (reachable && core.isOnline(q.getDeviceNumber())) {
                if (done.add(q.getDeviceNumber())) core.wake(q.getDeviceNumber());
                woken.add(q.getId());
            } else {
                offline.add(q.getId());
            }
        }
        dao.markWake(woken, "woken", now);
        dao.markWake(offline, "offline", now);
        return next.size();
    }

    /** Sends never-delivered expired commands again. Returns how many were queued. */
    int retryExpired(long now) {
        int n = 0;
        for (RetryRow r : dao.listRetries(MAX_ATTEMPTS, now - RETRY_WINDOW_MS, 200)) {
            try {
                if (dao.retry(r, now)) n++;
            } catch (Exception e) {
                warn("retrying a job command", e, now);
            }
        }
        if (n > 0) log.info("Policies plugin: {} expired job commands queued again", n);
        return n;
    }

    /** At most one warning a minute, so a broken database doesn't flood the log every second. */
    private void warn(String what, Exception e, long now) {
        if (now - lastWarn < 60_000L) {
            log.debug("Policies plugin: {} failed: {}", what, e.toString());
            return;
        }
        lastWarn = now;
        log.warn("Policies plugin: {} failed (retried on the next tick): {}", what, e.toString());
    }
}
