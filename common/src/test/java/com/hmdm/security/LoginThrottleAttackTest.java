package com.hmdm.security;

import com.hmdm.persistence.domain.User;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The attack review's attacker loops (v0.3.9), as regression tests with the bounds they measure. A fake clock runs a
 * simulated hour in milliseconds. Guess counts are checked passwords against one account; the attacker never knows
 * the password. Mixing the two sign-in routes (and the password-change check) is in the server's
 * SignInRoutesShareOneLimitTest.
 */
public class LoginThrottleAttackTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long WINDOW = LoginThrottle.SHORT_WINDOW_MINUTES * MINUTE;

    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private LoginThrottle throttle() {
        return new LoginThrottle(now::get, now::get, "", false, LoginThrottle.MAX_KEYS, LoginThrottleTest.SECRET);
    }

    private static User user(int id) {
        User u = new User();
        u.setId(id);
        u.setLogin("admin");
        return u;
    }

    private static String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    /** Wrong guesses at one account from {@code addresses}, round after round, for {@code durationMs}: checked ones. */
    private long guesses(LoginThrottle t, User u, String[] addresses, String cookie, long durationMs, long stepMs) {
        long start = now.get();
        long checked = 0;
        while (now.get() - start < durationMs) {
            for (String a : addresses) {
                if (t.begin(a, u, "admin", cookie).allowed()) {
                    checked++;   // its password would be checked here; it is wrong, so no succeeded()
                }
            }
            now.addAndGet(stepMs);
        }
        return checked;
    }

    private static String[] ipv4(int n, int first) {
        String[] a = new String[n];
        for (int i = 0; i < n; i++) {
            a[i] = first + "." + ((i >> 16) & 255) + "." + ((i >> 8) & 255) + "." + (i & 255);
        }
        return a;
    }

    /** 256 distinct /56s inside 2001:db8:0::/48 (the /56 boundary is the high byte of the 4th hextet). */
    private static String[] ipv6Slash48() {
        String[] a = new String[256];
        for (int i = 0; i < 256; i++) {
            a[i] = "2001:db8:0:" + Integer.toHexString(i << 8) + "::1";
        }
        return a;
    }

    /** 256 addresses inside one /56: one address key. */
    private static String[] ipv6Slash56() {
        String[] a = new String[256];
        for (int i = 0; i < 256; i++) {
            a[i] = "2001:db8:0:" + Integer.toHexString(i) + "::" + Integer.toHexString(i + 1);
        }
        return a;
    }

    // ---- checked guesses against one account --------------------------------------------------------------------------

    @Test
    public void oneAddressGets40AnHourAnd50InSixHours() {
        assertEquals(40, guesses(throttle(), user(42), new String[]{"203.0.113.7"}, null, HOUR, 1000));
        assertEquals(50, guesses(throttle(), user(42), new String[]{"203.0.113.7"}, null, 6 * HOUR, 1000));
    }

    @Test
    public void manyAddressesGetNoMoreThanTheAccountLimit80AnHour() {
        assertEquals(80, guesses(throttle(), user(42), ipv4(256, 10), null, HOUR, WINDOW));
        assertEquals("a botnet gains nothing", 80, guesses(throttle(), user(42), ipv4(65536, 10), null, HOUR, WINDOW));
    }

    @Test
    public void anIpv6Slash48Gets80AnHourAndASlash56Is40() {
        assertEquals(80, guesses(throttle(), user(42), ipv6Slash48(), null, HOUR, WINDOW));
        assertEquals("one /56 is one address", 40, guesses(throttle(), user(42), ipv6Slash56(), null, HOUR, MINUTE));
    }

    @Test
    public void aConcurrentBurstGetsExactlyTheAccountLimit() throws Exception {
        LoginThrottle t = throttle();
        User u = user(5);
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger checked = new AtomicInteger();
        List<Future<?>> all = new ArrayList<>();
        for (int th = 0; th < 64; th++) {
            final int id = th;
            all.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < 50; i++) {
                    if (t.begin("10.1." + id + "." + (i % 250 + 1), u, "admin", null).allowed()) {
                        checked.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : all) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals("3,200 concurrent attempts", LoginThrottle.ACCOUNT_FAILURES, checked.get());
    }

    @Test
    public void eachValidCookieIsTenMoreAndOnlyTheOwnerCanMintOne() {
        // A cookie is issued only by a full sign-in, so only someone with the password has one. Each is held to 10.
        LoginThrottle t = throttle();
        User v = user(77);
        long checked = 0;
        for (int c = 0; c < 5; c++) {
            String cookie = cookieValue(t.knownBrowserCookie(v, true));
            for (int i = 0; i < 30; i++) {
                if (t.begin("203.0.113." + ((c * 30 + i) % 250), v, "admin", cookie).allowed()) {
                    checked++;
                }
            }
        }
        assertEquals(5 * LoginThrottle.KNOWN_BROWSER_FAILURES, checked);
    }

    @Test
    public void fromTheAdminsOwnAddressAnAttackerGets10AQuarterHourAgainstThatAccountOnly() {
        LoginThrottle t = throttle();
        User admin = user(7);
        LoginThrottle.Verdict v = t.begin("198.51.100.20", admin, "admin", null);
        t.succeeded(v.attempt());
        t.signedIn(v.attempt(), admin);   // the admin signed in from the office NAT the attacker shares
        assertEquals(4 * LoginThrottle.KNOWN_ADDRESS_FAILURES,
                guesses(t, admin, new String[]{"198.51.100.20"}, null, HOUR, 1000));
    }

    // ---- lockout ------------------------------------------------------------------------------------------------------

    @Test
    public void anAttackLocksOutNewAddressesAndBrowsersOnlyTheAdminsOwnKeepSigningIn() {
        LoginThrottle t = throttle();
        User admin = user(7);
        // The admin signed in before, from the office (browser) and the API client's host (no cookie jar).
        LoginThrottle.Verdict office = t.begin("192.0.2.50", admin, "admin", null);
        t.succeeded(office.attempt());
        String cookie = cookieValue(t.signedIn(office.attempt(), admin));
        LoginThrottle.Verdict api = t.begin("192.0.2.60", admin, "admin", null);
        t.succeeded(api.attempt());
        t.signedIn(api.attempt(), admin);

        int refusedAt = -1;
        for (int i = 0; i < 100; i++) {
            if (!t.begin("198.51.100." + (i % 250 + 1), admin, "admin", null).allowed()) {
                refusedAt = i;
                break;
            }
        }
        assertEquals("the account locks after 20 failures", LoginThrottle.ACCOUNT_FAILURES, refusedAt);

        LoginThrottle.Verdict newAddress = t.begin("192.0.2.99", admin, "admin", null);
        assertFalse("a new address, without a cookie, is refused (documented)", newAddress.allowed());
        assertEquals(900, newAddress.retryAfterSeconds());
        assertTrue("the admin's browser, from anywhere", t.begin("203.0.113.200", admin, "admin", cookie).allowed());
        assertTrue("the admin's office, without the cookie", t.begin("192.0.2.50", admin, "admin", null).allowed());
        assertTrue("the API client's host", t.begin("192.0.2.60", admin, "admin", null).allowed());
    }

    @Test
    public void aBotnetFloodThatFillsTheAddressLimiterDoesntLockOutTheAdminsAddressOrBrowser() {
        LoginThrottle t = throttle();
        User admin = user(7);
        LoginThrottle.Verdict office = t.begin("192.0.2.50", admin, "admin", null);
        t.succeeded(office.attempt());
        String cookie = cookieValue(t.signedIn(office.attempt(), admin));
        String[] botnet = ipv4(65536, 10);
        int n = 0;
        int newAddressesLetIn = 0;
        for (int round = 0; round < 4; round++) {
            for (String a : botnet) {
                t.begin(a, admin, "admin", null);
                // A failure for a made-up name is let through (its bucket has room), so it holds an address key:
                // 65,536 of them fill the address limiter, and new addresses share its overflow.
                t.begin(a, null, "nobody" + (n++), null);
            }
            assertTrue("memory bounded", t.largestLimiter() <= LoginThrottle.MAX_KEYS + 1);
            for (int i = 0; i < 100; i++) {
                if (t.begin("192.0.3." + i, null, "someone" + i, null).allowed()) {
                    newAddressesLetIn++;
                }
            }
            LoginThrottle.Verdict fromOffice = t.begin("192.0.2.50", admin, "admin", null);
            assertTrue("the admin's office, round " + round, fromOffice.allowed());
            t.succeeded(fromOffice.attempt());
            LoginThrottle.Verdict browser = t.begin("203.0.113.200", admin, "admin", cookie);
            assertTrue("the admin's browser, round " + round, browser.allowed());
            t.succeeded(browser.attempt());
            assertFalse("a new address", t.begin("192.0.2.99", admin, "admin", null).allowed());
            now.addAndGet(WINDOW);
        }
        assertEquals("the flood did fill the address limiter: new addresses are refused", 0, newAddressesLetIn);
    }

    @Test
    public void theAdminsBrowserSignsInRightAfterARestartDuringAnAttack() {
        User admin = user(7);
        LoginThrottle before = throttle();
        LoginThrottle.Verdict v = before.begin("192.0.2.50", admin, "admin", null);
        before.succeeded(v.attempt());
        String cookie = cookieValue(before.signedIn(v.attempt(), admin));

        // A restart: counters and known addresses are gone, the cookie's key (from jwt.secretkey) is not.
        LoginThrottle after = new LoginThrottle(new AtomicLong(7)::get, now::get, "", false, LoginThrottle.MAX_KEYS,
                LoginThrottleTest.SECRET);
        for (int i = 0; i < 100; i++) {
            after.begin("198.51.100." + (i % 250 + 1), admin, "admin", null);
        }
        assertFalse("the office address is forgotten", after.begin("192.0.2.50", admin, "admin", null).allowed());
        assertTrue("the browser is still known", after.begin("192.0.2.50", admin, "admin", cookie).allowed());
    }

    @Test
    public void aFloodOfUnknownNamesNeverLocksARealAccount() {
        LoginThrottle t = throttle();
        // 30,000 distinct names in one window, from 3,000 addresses at their limit of 10 each.
        String[] addresses = ipv4(3000, 10);
        int n = 0;
        for (String a : addresses) {
            for (int i = 0; i < LoginThrottle.ADDRESS_FAILURES; i++) {
                t.begin(a, null, "flood" + (n++), null);
            }
        }
        assertTrue("name keys bounded", t.nameKeys() <= LoginThrottle.NAME_BUCKETS);
        assertTrue("memory bounded", t.largestLimiter() <= LoginThrottle.MAX_KEYS + 1);
        // Every real account, from a new address, still gets its whole own limit.
        for (int id = 1; id <= 50; id++) {
            int checked = 0;
            for (int i = 0; i < 30; i++) {
                if (t.begin("198.51." + id + "." + i, user(id), "u" + id, null).allowed()) {
                    checked++;
                }
            }
            assertEquals("account " + id, LoginThrottle.ACCOUNT_FAILURES, checked);
        }
    }

    @Test
    public void namesCantPushARealAccountIntoTheOverflowKey() {
        // The review's overflow mechanics, at a cap of 50: before, 50 names filled the account limiter and the
        // overflow's 100, and then a fresh admin key was refused.
        LoginThrottle t = new LoginThrottle(now::get, now::get, "", false, 50, LoginThrottleTest.SECRET);
        for (int i = 0; i < 300; i++) {
            t.begin((String) null, null, "flood" + i, null);
        }
        int checked = 0;
        for (int i = 0; i < 30; i++) {
            if (t.begin((String) null, user(999), "admin", null).allowed()) {
                checked++;
            }
        }
        assertEquals("a fresh admin key has its own limit", LoginThrottle.ACCOUNT_FAILURES, checked);
    }

    // ---- the slots one attempt holds ------------------------------------------------------------------------------------

    @Test
    public void aDoubleSuccessCantFreeAnotherLiveAttemptsSlot() {
        LoginThrottle t = throttle();
        User u = user(5);
        LoginThrottle.Verdict v1 = t.begin("10.9.9.1", u, "admin", null);
        LoginThrottle.Verdict v2 = t.begin("10.9.9.2", u, "admin", null);   // still live: its password is a guess
        t.succeeded(v1.attempt());
        t.succeeded(v1.attempt());
        int more = 0;
        for (int i = 0; i < 25; i++) {
            if (t.begin("10.9.8." + i, u, "admin", null).allowed()) {
                more++;
            }
        }
        assertTrue(v2.allowed());
        assertEquals("the live attempt keeps its slot", LoginThrottle.ACCOUNT_FAILURES - 1, more);
    }
}
