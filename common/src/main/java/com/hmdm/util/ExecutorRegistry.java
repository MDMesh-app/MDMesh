/*
 * MDMesh — process-wide registry of thread pools so a web-app stop can end them.
 */
package com.hmdm.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <p>Every long-lived thread pool the server creates registers here so that
 * {@code Initializer.contextDestroyed} can shut them all down when Tomcat stops or undeploys the
 * web application.</p>
 *
 * <p>Why: the pools used to rely on {@code Runtime.addShutdownHook}, which only runs when the JVM
 * exits — and the JVM never exits, because these non-daemon pool threads keep it alive after the
 * container has stopped every service. The result was a Tomcat that ignored {@code catalina.sh stop}
 * and had to be killed.</p>
 */
public final class ExecutorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExecutorRegistry.class);
    private static final List<ExecutorService> POOLS = new ArrayList<>();

    private ExecutorRegistry() {
    }

    /** Registers a pool and returns it, so call sites stay one-liners. */
    public static synchronized <T extends ExecutorService> T register(T executor) {
        POOLS.add(executor);
        return executor;
    }

    /**
     * Shuts down every registered pool: orderly first, then {@code shutdownNow} for anything still
     * running after {@code graceSeconds}. Clears the registry; safe to call repeatedly.
     *
     * @return the number of pools that were shut down by this call.
     */
    public static int shutdownAll(long graceSeconds) {
        final List<ExecutorService> pools;
        synchronized (ExecutorRegistry.class) {
            pools = new ArrayList<>(POOLS);
            POOLS.clear();
        }
        for (ExecutorService pool : pools) {
            try {
                pool.shutdown();
            } catch (RuntimeException e) {
                log.warn("shutdown() failed for {}: {}", pool, e.toString());
            }
        }
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(graceSeconds);
        for (ExecutorService pool : pools) {
            try {
                long left = Math.max(1L, deadline - System.nanoTime());
                if (!pool.awaitTermination(left, TimeUnit.NANOSECONDS)) {
                    pool.shutdownNow();
                    pool.awaitTermination(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("termination failed for {}: {}", pool, e.toString());
            }
        }
        if (!pools.isEmpty()) {
            log.info("Stopped {} thread pool(s) on web-app shutdown", pools.size());
        }
        return pools.size();
    }
}
