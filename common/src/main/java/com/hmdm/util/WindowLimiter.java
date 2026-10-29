/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Allows at most {@code max} actions per key within any sliding time window. A refused attempt takes no slot, so it
 * does not extend the window.
 *
 * <p>In memory, so it is per server process: a restart forgets it, and several nodes would each allow {@code max}.
 * MDMesh runs a single server node.</p>
 *
 * <p><b>Invariants.</b></p>
 * <ul>
 *   <li>A slot stays taken until its window has passed, or until the one action that took it gives it back
 *       ({@link #release(Grant)}). Nothing else frees a slot: no eviction of live slots, no reset. Each slot carries
 *       the unique id of the grant that took it, and a release removes that slot only, once: a second release of the
 *       same grant, or one after the slot expired, gives back nothing. ({@link #release(String)}, which gives back
 *       the key's most recent slot whoever took it, is for callers that don't need this, such as the kiosk alert
 *       limits; the sign-in limits never use it.)</li>
 *   <li>Time is whatever {@code clock} says; use {@link #MONOTONIC}, which never jumps. A wall clock set forward (NTP
 *       step, an operator's {@code date}) would expire every slot at once.</li>
 *   <li><b>Never fails open.</b> With a key cap, memory holds at most {@code maxKeys} keys plus one: a key the limiter
 *       can't hold (the cap is full of live keys) is counted in a single shared overflow key, with its own limit
 *       {@code overflowMax}. So a flood of new keys degrades to one coarse global limit for the keys that don't fit,
 *       while the keys already held stay limited as before.</li>
 * </ul>
 */
public final class WindowLimiter {

    /** A monotonic millisecond clock (System.nanoTime), immune to wall-clock steps. */
    public static final LongSupplier MONOTONIC = () -> System.nanoTime() / 1_000_000L;

    /** The key every key that doesn't fit is counted under. No caller's key starts with a NUL. */
    static final String OVERFLOW = "\u0000overflow";

    /** Past this many keys, keys with only expired slots are dropped on the next acquire, so memory follows the active keys. */
    static final int PRUNE_THRESHOLD = 1000;

    /** A full prune walks every key, so it runs at most this often (a flood of new keys must not make each one pay). */
    static final long PRUNE_INTERVAL_MS = 1000;

    /** One acquire's answer: whether it got a slot, under which key (its own or the overflow key), and which slot. */
    public static final class Grant {
        private final String key;
        private final long id;
        private final boolean granted;

        private Grant(String key, long id, boolean granted) {
            this.key = key;
            this.id = id;
            this.granted = granted;
        }

        public boolean granted() {
            return granted;
        }

        /** The key the slot was taken under, or that refused it: the caller's, or the shared overflow key. */
        public String key() {
            return key;
        }
    }

    private final LongSupplier clock;
    private final long windowMs;
    private final int max;
    private final int maxKeys;
    private final int overflowMax;
    private volatile long lastPrune;
    private volatile boolean pruned;
    /** Grant ids: unique per limiter, so a slot is never mistaken for another taken in the same millisecond. */
    private final AtomicLong ids = new AtomicLong();
    /** Per key, its taken slots, oldest first. Each deque is only touched inside a map compute. */
    private final Map<String, Deque<Slot>> taken = new ConcurrentHashMap<>();

    /** A taken slot: when, and the id of the grant that took it. */
    private static final class Slot {
        final long time;
        final long id;

        Slot(long time, long id) {
            this.time = time;
            this.id = id;
        }
    }

    /**
     * No key cap: for keys the server picks (a device, a customer, an account of the recovery mail).
     *
     * @param clock    the current time in milliseconds ({@link #MONOTONIC}; injectable for tests)
     * @param windowMs how long a taken slot stays taken
     * @param max      slots per key within one window
     */
    public WindowLimiter(LongSupplier clock, long windowMs, int max) {
        this(clock, windowMs, max, Integer.MAX_VALUE, 0);
    }

    /**
     * With a key cap: for keys a client picks (addresses, login names).
     *
     * @param maxKeys     at most this many keys are held, plus the overflow key
     * @param overflowMax slots within one window shared by every key that doesn't fit
     */
    public WindowLimiter(LongSupplier clock, long windowMs, int max, int maxKeys, int overflowMax) {
        if (clock == null || windowMs <= 0 || max < 0 || maxKeys <= 0 || overflowMax < 0) {
            throw new IllegalArgumentException("needs a clock, windowMs > 0, max >= 0, maxKeys > 0, overflowMax >= 0");
        }
        this.clock = clock;
        this.windowMs = windowMs;
        this.max = max;
        this.maxKeys = maxKeys;
        this.overflowMax = overflowMax;
    }

    /** @return true, taking a slot, when the key has used fewer than its limit of slots within the last window. */
    public boolean tryAcquire(String key) {
        return acquire(key).granted;
    }

    /** Takes a slot for {@code key} (or, when it doesn't fit, for the overflow key) if one is free. */
    public Grant acquire(String key) {
        long now = clock.getAsLong();
        String k = key;
        if (maxKeys != Integer.MAX_VALUE && !taken.containsKey(k) && sizeWithoutOverflow() >= maxKeys) {
            maybePrune(now);
            if (sizeWithoutOverflow() >= maxKeys) {
                k = OVERFLOW;
            }
        }
        final String used = k;
        final int limit = OVERFLOW.equals(used) ? overflowMax : max;
        long[] id = {0};
        taken.compute(used, (ignored, slots) -> {
            Deque<Slot> t = slots == null ? new ArrayDeque<>() : slots;
            dropExpired(t, now);
            if (t.size() < limit) {
                id[0] = ids.incrementAndGet();
                t.addLast(new Slot(now, id[0]));
            }
            return t.isEmpty() ? null : t;
        });
        if (taken.size() > PRUNE_THRESHOLD) {
            maybePrune(now);
        }
        return new Grant(used, id[0], id[0] != 0);
    }

    /** Gives back the key's most recent slot: for an action that was allowed but then could not be carried out. */
    public void release(String key) {
        taken.computeIfPresent(key, (k, t) -> {
            t.pollLast();
            return t.isEmpty() ? null : t;
        });
    }

    /**
     * Gives back the slot this grant took, and only that one, once: when it has expired or was given back already,
     * nothing is given back (never another action's slot instead). A refused grant has nothing to give back.
     */
    public void release(Grant grant) {
        if (grant == null || !grant.granted) {
            return;
        }
        taken.computeIfPresent(grant.key, (k, t) -> {
            for (java.util.Iterator<Slot> it = t.descendingIterator(); it.hasNext(); ) {
                if (it.next().id == grant.id) {
                    it.remove();
                    break;
                }
            }
            return t.isEmpty() ? null : t;
        });
    }

    /** @return 0 when the key has a free slot now, else the milliseconds until its oldest slot expires. */
    public long millisUntilFree(String key) {
        long now = clock.getAsLong();
        int limit = OVERFLOW.equals(key) ? overflowMax : max;
        long[] wait = {0};
        taken.computeIfPresent(key, (k, t) -> {
            dropExpired(t, now);
            if (!t.isEmpty() && t.size() >= limit) {
                wait[0] = Math.max(1, t.peekFirst().time + windowMs - now);
            }
            return t.isEmpty() ? null : t;
        });
        return wait[0] == 0 && limit == 0 ? windowMs : wait[0];
    }

    /** The number of keys remembered right now, the overflow key included. */
    public int size() {
        return taken.size();
    }

    private int sizeWithoutOverflow() {
        return taken.size() - (taken.containsKey(OVERFLOW) ? 1 : 0);
    }

    private void maybePrune(long now) {
        if (pruned && now - lastPrune < PRUNE_INTERVAL_MS) {
            return;
        }
        lastPrune = now;
        pruned = true;
        for (String key : taken.keySet()) {
            taken.computeIfPresent(key, (k, t) -> {
                dropExpired(t, now);
                return t.isEmpty() ? null : t;
            });
        }
    }

    private void dropExpired(Deque<Slot> slots, long now) {
        while (!slots.isEmpty() && now - slots.peekFirst().time >= windowMs) {
            slots.pollFirst();
        }
    }
}
