/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.plugins.audit.guice.module;

import com.hmdm.plugins.audit.persistence.AuditDAO;
import com.hmdm.util.BackgroundTaskRunnerService;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The background scrub's runs: done, time-boxed, and retried with a growing wait after failures. */
public class AuditScrubTaskModuleTest {

    /** What each scrubOldRecords call does, in order; then 0 (more to do). */
    private final Deque<Supplier<Integer>> batches = new ArrayDeque<>();
    private int scrubCalls;
    private int extendCalls;
    private long now = 1_000_000;
    private final FutureTask<Void> future = new FutureTask<>(() -> null);

    private final AuditDAO dao = new AuditDAO(null) {
        @Override
        public int scrubOldRecords(int limit) {
            scrubCalls++;
            now += 100;
            Supplier<Integer> next = batches.poll();
            return next == null ? 0 : next.get();
        }

        @Override
        public void extendScrubEnd() {
            extendCalls++;
        }
    };

    private final AuditScrubTaskModule module = new AuditScrubTaskModule(dao, new BackgroundTaskRunnerService() {
        @Override
        public Future<?> submitRepeatableTask(Runnable task, long initialDelay, long period, TimeUnit unit) {
            return future;
        }
    });

    {
        module.clock = () -> now;
        module.init();
    }

    @Test
    public void stopsForGoodOnceDone() {
        batches.add(() -> 3);
        batches.add(() -> -1);
        module.run();
        assertEquals(2, scrubCalls);
        assertTrue(future.isCancelled());
        assertEquals(1, extendCalls);
    }

    @Test
    public void worksAFewSecondsPerRun() {
        module.run();                                       // never done: 100 ms a batch, 3 s a run
        assertEquals(AuditScrubTaskModule.RUN_MILLIS / 100, scrubCalls);
        assertFalse(future.isCancelled());
        module.run();
        assertEquals(2 * AuditScrubTaskModule.RUN_MILLIS / 100, scrubCalls);
        assertEquals("the end is moved once, at the first run", 1, extendCalls);
    }

    @Test
    public void waitsLongerAfterEachFailureInARowUpToFiveMinutes() {
        long[] waits = {5000, 10000, 20000, 40000, 80000, 160000, 300000, 300000};
        for (long wait : waits) {
            batches.add(() -> {
                throw new IllegalStateException("database down");
            });
            int before = scrubCalls;
            module.run();
            assertEquals(before + 1, scrubCalls);
            now += wait - 1;
            module.run();                                   // still waiting
            assertEquals(before + 1, scrubCalls);
            now += 1;
        }
        batches.add(() -> -1);
        module.run();
        assertTrue(future.isCancelled());
    }

    @Test
    public void aSuccessStartsTheWaitOver() {
        batches.add(() -> {
            throw new IllegalStateException("once");
        });
        module.run();
        now += AuditScrubTaskModule.FIRST_BACKOFF_MILLIS;
        batches.add(() -> 1);
        batches.add(() -> {
            throw new IllegalStateException("again");
        });
        module.run();
        int calls = scrubCalls;
        now += AuditScrubTaskModule.FIRST_BACKOFF_MILLIS;
        module.run();
        assertTrue("the wait is the first one again", scrubCalls > calls);
    }

    @Test
    public void tellsTheScrubsOwnTimeoutsFromOtherFailures() {
        assertTrue(AuditScrubTaskModule.isTimeout(new RuntimeException(new java.sql.SQLException("lock", "55P03"))));
        assertTrue(AuditScrubTaskModule.isTimeout(new java.sql.SQLException("cancelled", "57014")));
        assertFalse(AuditScrubTaskModule.isTimeout(new RuntimeException(new java.sql.SQLException("dup", "23505"))));
        assertFalse(AuditScrubTaskModule.isTimeout(new IllegalStateException("database down")));
        // A timeout is retried with the same backoff
        batches.add(() -> {
            throw new RuntimeException(new java.sql.SQLException("lock", "55P03"));
        });
        module.run();
        int calls = scrubCalls;
        now += AuditScrubTaskModule.FIRST_BACKOFF_MILLIS - 1;
        module.run();
        assertEquals(calls, scrubCalls);
    }

    @Test
    public void aCycleOfCausesEndsTheTimeoutCheck() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertFalse(AuditScrubTaskModule.isTimeout(a));
    }

    @Test
    public void timeoutsThatKeepComingAreSaidAtWarnEveryThirdTime() {
        java.util.List<org.apache.log4j.Level> levels = new java.util.ArrayList<>();
        org.apache.log4j.AppenderSkeleton capture = new org.apache.log4j.AppenderSkeleton() {
            @Override
            protected void append(org.apache.log4j.spi.LoggingEvent event) {
                levels.add(event.getLevel());
            }

            @Override
            public void close() {
            }

            @Override
            public boolean requiresLayout() {
                return false;
            }
        };
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(AuditScrubTaskModule.class);
        org.apache.log4j.Level previous = logger.getLevel();
        logger.setLevel(org.apache.log4j.Level.ALL);
        logger.addAppender(capture);
        try {
            for (int i = 0; i < 7; i++) {
                batches.add(() -> {
                    throw new RuntimeException(new java.sql.SQLException("cancelled", "57014"));
                });
                module.run();
                now += AuditScrubTaskModule.MAX_BACKOFF_MILLIS;
            }
        } finally {
            logger.removeAppender(capture);
            logger.setLevel(previous);
        }
        // Every third timeout in a row is a WARN
        assertEquals(java.util.Arrays.asList(org.apache.log4j.Level.INFO, org.apache.log4j.Level.INFO,
                org.apache.log4j.Level.WARN, org.apache.log4j.Level.INFO, org.apache.log4j.Level.INFO,
                org.apache.log4j.Level.WARN, org.apache.log4j.Level.INFO), levels);
    }

    @Test
    public void anErrorIsRetriedAndAVirtualMachineErrorIsNot() {
        batches.add(() -> {
            throw new AssertionError("a bug");
        });
        module.run();                                       // caught, logged, retried later
        now += AuditScrubTaskModule.FIRST_BACKOFF_MILLIS;
        batches.add(() -> {
            throw new OutOfMemoryError("heap");
        });
        try {
            module.run();
            fail("a VirtualMachineError must not be swallowed");
        } catch (OutOfMemoryError expected) {
            // The runner ends the task
        }
    }
}
