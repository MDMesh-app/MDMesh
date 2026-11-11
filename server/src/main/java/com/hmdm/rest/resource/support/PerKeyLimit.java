/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource.support;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * At most {@code max} callers at once per key; the rest are turned away at once instead of queueing. Enrollments of one
 * code run one after another on its database lock, each holding a pooled connection while it waits: this keeps a burst
 * on one code (a batch of devices switched on together) from taking the whole pool. A key's entry goes when idle.
 */
public final class PerKeyLimit<K> {

    private final int max;
    private final ConcurrentMap<K, Integer> inFlight = new ConcurrentHashMap<>();

    public PerKeyLimit(int max) {
        this.max = max;
    }

    /** @return true if the caller may go ahead; it must then call {@link #exit} once. */
    public boolean tryEnter(K key) {
        boolean[] entered = {false};
        inFlight.compute(key, (k, n) -> {
            int current = n == null ? 0 : n;
            if (current >= max) return n;
            entered[0] = true;
            return current + 1;
        });
        return entered[0];
    }

    public void exit(K key) {
        inFlight.computeIfPresent(key, (k, n) -> n <= 1 ? null : n - 1);
    }

    /** Keys with callers in flight (tests). */
    int trackedKeys() {
        return inFlight.size();
    }
}
