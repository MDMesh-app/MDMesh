/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.plugins.audit.guice.module;

import com.google.inject.Inject;
import com.hmdm.plugin.PluginTaskModule;
import com.hmdm.plugins.audit.persistence.AuditDAO;
import com.hmdm.util.BackgroundTaskRunnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * <p>Scrubs the audit records older releases stored with secrets in them (AuditDAO.scrubOldRecords), in the background
 * after startup: small batches by id, a few seconds of work at a time, so the server answers (and the update's health
 * check passes) while it runs. It goes on from where it stopped after a restart, and stops for good once done. After a
 * failure it waits longer each time (up to {@link #MAX_BACKOFF_MILLIS}) before it tries again.</p>
 */
public class AuditScrubTaskModule implements PluginTaskModule {

    private static final Logger logger = LoggerFactory.getLogger(AuditScrubTaskModule.class);

    /** Records per transaction. */
    public static final int BATCH = 500;

    /** Work per run, then the runner is left to its other tasks until the next. */
    static final long RUN_MILLIS = 3000;

    /** The wait after a first failure, doubled at each one in a row, up to {@link #MAX_BACKOFF_MILLIS}. */
    static final long FIRST_BACKOFF_MILLIS = 5000;
    static final long MAX_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final AuditDAO auditDAO;
    private final BackgroundTaskRunnerService taskRunner;
    private final AtomicReference<Future<?>> scheduled = new AtomicReference<>();
    /** The clock: a test sets its own. */
    LongSupplier clock = System::currentTimeMillis;
    private long rewritten;
    private boolean started;
    private boolean announced;
    private long backoff;
    private int timeoutsInARow;

    /** Every this many timeouts in a row, one is logged at WARN. */
    static final int TIMEOUTS_TO_WARN = 3;
    private long nextAttempt;

    @Inject
    public AuditScrubTaskModule(AuditDAO auditDAO, BackgroundTaskRunnerService taskRunner) {
        this.auditDAO = auditDAO;
        this.taskRunner = taskRunner;
    }

    @Override
    public void init() {
        scheduled.set(taskRunner.submitRepeatableTask(this::run, 30, 5, TimeUnit.SECONDS));
    }

    /** One run: batches until done or {@link #RUN_MILLIS} have passed. */
    void run() {
        long now = clock.getAsLong();
        if (now < nextAttempt) {
            return;
        }
        long until = now + RUN_MILLIS;
        try {
            if (!started) {
                auditDAO.extendScrubEnd();
                started = true;
            }
            while (clock.getAsLong() < until) {
                int batch = auditDAO.scrubOldRecords(BATCH);
                backoff = 0;
                timeoutsInARow = 0;
                if (batch < 0) {
                    if (announced) {
                        logger.info("Audit records from before this release scrubbed: {} rewritten", rewritten);
                    }
                    Future<?> task = scheduled.get();
                    if (task != null) {
                        task.cancel(false);
                    }
                    return;
                }
                if (!announced) {
                    logger.info("Scrubbing secrets from the audit records older releases stored, in the background");
                    announced = true;
                }
                rewritten += batch;
            }
        } catch (VirtualMachineError e) {
            logger.error("Audit record scrub stopped: {}", e.getClass().getName());
            throw e;
        } catch (Throwable e) {
            // A repeatable task that throws is never run again: wait, then try again
            backoff = backoff == 0 ? FIRST_BACKOFF_MILLIS : Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
            nextAttempt = clock.getAsLong() + backoff;
            if (isTimeout(e)) {
                // The scrub's own lock or statement timeout: expected while another server holds the batch. Said
                // once at WARN when it keeps happening, so a batch that always times out is seen.
                timeoutsInARow++;
                if (timeoutsInARow % TIMEOUTS_TO_WARN == 0) {
                    logger.warn("Audit record scrub timed out {} times in a row; another server may be scrubbing, or "
                            + "a batch is too slow; retrying in {} s", timeoutsInARow, backoff / 1000);
                } else {
                    logger.info("Audit record scrub: another server is scrubbing, or a batch was slow; retrying in "
                            + "{} s", backoff / 1000);
                }
            } else {
                timeoutsInARow = 0;
                logger.warn("Audit record scrub failed, will retry in {} s: {}", backoff / 1000,
                        e.getClass().getName());
            }
        }
    }

    /** Whether {@code e} is the scrub's lock_timeout (55P03) or statement_timeout (57014), within 16 causes. */
    static boolean isTimeout(Throwable e) {
        Throwable cause = e;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof SQLException) {
                String state = ((SQLException) cause).getSQLState();
                if ("55P03".equals(state) || "57014".equals(state)) {
                    return true;
                }
            }
        }
        return false;
    }
}
