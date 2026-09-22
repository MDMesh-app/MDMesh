package com.hmdm.util;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The server's thread pools used to be shut down only by JVM shutdown hooks, which never run on a Tomcat
 * context stop, so the non-daemon pool threads kept the JVM alive after {@code catalina.sh stop}.
 * {@link ExecutorRegistry#shutdownAll(long)} is what {@code Initializer.contextDestroyed} drains.
 */
public class ExecutorRegistryTest {

    @After
    public void reset() {
        ExecutorRegistry.shutdownAll(1);
    }

    @Test
    public void shutdownAllTerminatesRegisteredPoolsIncludingPeriodicTasks() throws Exception {
        ScheduledExecutorService scheduled = ExecutorRegistry.register(Executors.newScheduledThreadPool(1));
        ExecutorService fixed = ExecutorRegistry.register(Executors.newFixedThreadPool(2));
        CountDownLatch ticked = new CountDownLatch(1);
        scheduled.scheduleAtFixedRate(ticked::countDown, 0, 10, TimeUnit.MILLISECONDS);
        assertTrue("periodic task should be running", ticked.await(2, TimeUnit.SECONDS));

        int stopped = ExecutorRegistry.shutdownAll(5);

        assertEquals(2, stopped);
        assertTrue(scheduled.isTerminated());
        assertTrue(fixed.isTerminated());
    }

    @Test
    public void shutdownAllIsIdempotentAndEmptiesTheRegistry() {
        ExecutorRegistry.register(Executors.newSingleThreadExecutor());
        assertEquals(1, ExecutorRegistry.shutdownAll(5));
        assertEquals(0, ExecutorRegistry.shutdownAll(5));
    }

    @Test
    public void registerReturnsTheSameInstanceSoCallSitesStayOneLiners() {
        ExecutorService e = Executors.newSingleThreadExecutor();
        assertTrue(ExecutorRegistry.register(e) == e);
    }
}
