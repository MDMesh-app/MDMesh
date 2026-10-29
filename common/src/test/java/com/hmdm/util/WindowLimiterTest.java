/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.util;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WindowLimiterTest {

    private static final long HOUR = 60L * 60L * 1000L;

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private WindowLimiter limiter(int max) {
        return new WindowLimiter(now::get, HOUR, max);
    }

    @Test
    public void allowsTheFirstAndRefusesTheRestWithinTheWindow() {
        WindowLimiter once = limiter(1);
        assertTrue(once.tryAcquire("1234"));
        assertFalse(once.tryAcquire("1234"));
        now.addAndGet(HOUR - 1);
        assertFalse(once.tryAcquire("1234"));
    }

    @Test
    public void allowsAgainOnceTheWindowHasPassed() {
        WindowLimiter once = limiter(1);
        assertTrue(once.tryAcquire("1234"));
        now.addAndGet(HOUR);
        assertTrue(once.tryAcquire("1234"));
        assertFalse(once.tryAcquire("1234"));
    }

    @Test
    public void allowsUpToTheMaximumPerWindow() {
        WindowLimiter three = limiter(3);
        assertTrue(three.tryAcquire("c1"));
        now.addAndGet(10);
        assertTrue(three.tryAcquire("c1"));
        assertTrue(three.tryAcquire("c1"));
        assertFalse(three.tryAcquire("c1"));
        // The window slides: the first slot frees up an hour after it was used, the others 10 ms later.
        now.addAndGet(HOUR - 10);
        assertTrue(three.tryAcquire("c1"));
        assertFalse(three.tryAcquire("c1"));
    }

    @Test
    public void keysAreIndependent() {
        WindowLimiter once = limiter(1);
        assertTrue(once.tryAcquire("1234"));
        assertTrue(once.tryAcquire("5678"));
        assertFalse(once.tryAcquire("1234"));
    }

    @Test
    public void aRefusedAttemptDoesNotExtendTheWindow() {
        WindowLimiter once = limiter(1);
        assertTrue(once.tryAcquire("1234"));
        now.addAndGet(HOUR / 2);
        assertFalse(once.tryAcquire("1234"));
        now.addAndGet(HOUR / 2);
        assertTrue(once.tryAcquire("1234"));
    }

    @Test
    public void releaseGivesBackTheSlotJustTaken() {
        WindowLimiter two = limiter(2);
        assertTrue(two.tryAcquire("c1"));
        assertTrue(two.tryAcquire("c1"));
        two.release("c1");
        assertTrue(two.tryAcquire("c1"));
        assertFalse(two.tryAcquire("c1"));
        two.release("unknown"); // harmless
    }

    @Test
    public void forgetsExpiredKeysSoMemoryStaysBounded() {
        WindowLimiter once = limiter(1);
        for (int i = 0; i < WindowLimiter.PRUNE_THRESHOLD + 10; i++) {
            assertTrue(once.tryAcquire("dev-" + i));
        }
        now.addAndGet(HOUR);
        once.tryAcquire("fresh");
        assertEquals(1, once.size());
    }

    @Test
    public void keysThatDontFitShareOneOverflowLimitSoItNeverFailsOpen() {
        WindowLimiter capped = new WindowLimiter(now::get, HOUR, 1, 3, 2);
        assertTrue(capped.tryAcquire("a"));
        assertTrue(capped.tryAcquire("b"));
        assertTrue(capped.tryAcquire("c"));
        // Full of live keys: new keys share the overflow key's 2 slots, then are refused.
        WindowLimiter.Grant d = capped.acquire("d");
        assertTrue(d.granted());
        assertEquals(WindowLimiter.OVERFLOW, d.key());
        assertTrue(capped.tryAcquire("e"));
        assertFalse(capped.tryAcquire("f"));
        assertFalse(capped.tryAcquire("d"));
        assertEquals("memory: the cap plus the overflow key", 4, capped.size());
        assertFalse("the keys it holds are still limited", capped.tryAcquire("a"));
        assertTrue(capped.millisUntilFree(WindowLimiter.OVERFLOW) > 0);
        // Once theirs expire, new keys get keys of their own again.
        now.addAndGet(HOUR);
        assertEquals("d", capped.acquire("d").key());
        assertFalse(capped.tryAcquire("d"));
    }

    @Test
    public void releasingAGrantGivesBackThatSlotOnly() {
        WindowLimiter two = limiter(2);
        WindowLimiter.Grant first = two.acquire("c1");
        now.addAndGet(HOUR - 1);
        two.acquire("c1");
        now.addAndGet(1);
        // The first slot has expired: giving it back now must not give back the second (someone else's) slot.
        two.release(first);
        assertTrue(two.tryAcquire("c1"));
        assertFalse(two.tryAcquire("c1"));
        WindowLimiter.Grant refused = two.acquire("c1");
        assertFalse(refused.granted());
        two.release(refused);
        assertFalse("a refused grant gives nothing back", two.tryAcquire("c1"));
    }

    @Test
    public void aGrantIsGivenBackOnceSoADoubleReleaseCantFreeAnotherAttemptsSlot() {
        WindowLimiter two = limiter(2);
        // Two live attempts in the same millisecond: their slots differ only by their grant.
        WindowLimiter.Grant first = two.acquire("c1");
        WindowLimiter.Grant second = two.acquire("c1");
        assertTrue(first.granted());
        assertTrue(second.granted());
        two.release(first);
        two.release(first);   // a second release of the same grant is a no-op
        assertTrue("the first slot is free again", two.tryAcquire("c1"));
        assertFalse("the second attempt still holds its slot", two.tryAcquire("c1"));
        two.release(second);
        two.release(second);
        assertTrue(two.tryAcquire("c1"));
        assertFalse(two.tryAcquire("c1"));
    }

    @Test
    public void releaseByKeyStillGivesBackTheMostRecentSlotAsBefore() {
        // KioskAlertLimits and the recovery mail limit use tryAcquire / release(key): unchanged.
        WindowLimiter once = limiter(1);
        assertTrue(once.tryAcquire("dev"));
        once.release("dev");
        once.release("dev");   // nothing left to give back
        assertTrue(once.tryAcquire("dev"));
        assertFalse(once.tryAcquire("dev"));
        now.addAndGet(HOUR);
        assertTrue(once.tryAcquire("dev"));
    }

    @Test
    public void theMonotonicClockNeverGoesBack() {
        long a = WindowLimiter.MONOTONIC.getAsLong();
        long b = WindowLimiter.MONOTONIC.getAsLong();
        assertTrue(b >= a);
    }

    @Test
    public void millisUntilFreeSaysWhenTheOldestSlotExpires() {
        WindowLimiter two = limiter(2);
        assertEquals(0, two.millisUntilFree("c1"));
        two.tryAcquire("c1");
        assertEquals("a slot is still free", 0, two.millisUntilFree("c1"));
        now.addAndGet(1000);
        two.tryAcquire("c1");
        assertEquals(HOUR - 1000, two.millisUntilFree("c1"));
        now.addAndGet(HOUR - 1000);
        assertEquals(0, two.millisUntilFree("c1"));
    }
}
