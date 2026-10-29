package com.hmdm.security;

import com.hmdm.persistence.domain.User;
import org.junit.Test;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Attacker loops against each invariant in {@link LoginThrottle}'s Javadoc. The attacker varies the login's case and
 * picks the e-mail instead of the login (resolved the way the database resolves them), rotates addresses as far as the
 * trusted-proxy rules let it, interleaves a real user's successes, floods new keys past the memory cap and bursts
 * concurrently. Both sign-in routes make the same call on one instance; alternating the real routes is in the server's
 * SignInRoutesShareOneLimitTest. Every loop is seeded, so a failure replays.
 */
public class LoginThrottleTest {

    private static final long MINUTE = 60L * 1000L;
    private static final long WINDOW = LoginThrottle.SHORT_WINDOW_MINUTES * MINUTE;

    /** A stand-in for the install's jwt.secretkey. */
    static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final LoginThrottle throttle = new LoginThrottle(now::get, SECRET);

    private LoginThrottle capped(int cap) {
        return new LoginThrottle(now::get, now::get, "", false, cap, SECRET);
    }

    private static User user(int id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    /** The accounts of the install, resolved like UnsecureDAO.findByLoginOrEmail: lower(login), then lower(email). */
    private final Map<String, User> byLogin = new HashMap<>();
    private final Map<String, User> byEmail = new HashMap<>();

    private User account(int id, String login, String email) {
        User u = new User();
        u.setId(id);
        u.setLogin(login);
        u.setEmail(email);
        byLogin.put(login.toLowerCase(Locale.ROOT), u);
        byEmail.put(email.toLowerCase(Locale.ROOT), u);
        return u;
    }

    private User resolve(String login) {
        User u = byLogin.get(login.toLowerCase(Locale.ROOT));
        return u != null ? u : byEmail.get(login.toLowerCase(Locale.ROOT));
    }

    /** One failed attempt; true when its password was checked. */
    private boolean failFrom(String address, String login) {
        return throttle.begin(address, resolve(login), login, null).allowed();
    }

    /** {@code s} in random letter case. */
    private static String spelling(Random r, String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            b.append(r.nextBoolean() ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return b.toString();
    }

    /** Asserts that no sliding window of {@code windowMs} holds more than {@code max} of {@code times}. */
    private static void assertAtMost(String what, List<Long> times, long windowMs, int max) {
        Deque<Long> inWindow = new ArrayDeque<>();
        for (long t : times) {
            while (!inWindow.isEmpty() && t - inWindow.peekFirst() >= windowMs) {
                inWindow.pollFirst();
            }
            inWindow.addLast(t);
            assertTrue(what + ": " + inWindow.size() + " checked guesses in one window, limit " + max,
                    inWindow.size() <= max);
        }
    }

    // ---- invariant 1: counted before checked, whatever the spelling or address --------------------------------------

    @Test
    public void manyAddressesAndSpellingsGetNoMoreThanTheAccountLimit() {
        account(1, "admin", "Admin@Example.com");
        Random r = new Random(1);
        List<Long> checked = new ArrayList<>();
        for (int i = 0; i < 20000; i++) {
            now.addAndGet(r.nextInt(3000));
            String login = r.nextBoolean() ? spelling(r, "admin") : spelling(r, "admin@example.com");
            if (failFrom("203.0." + r.nextInt(256) + "." + r.nextInt(256), login)) {
                checked.add(now.get());
            }
        }
        assertTrue("the loop made guesses", checked.size() > LoginThrottle.ACCOUNT_FAILURES);
        assertAtMost("one account from many addresses", checked, WINDOW, LoginThrottle.ACCOUNT_FAILURES);
    }

    @Test
    public void oneAddressGetsNoMoreThanItsLimitsAcrossAccountsAndNames() {
        for (int i = 0; i < 50; i++) {
            account(i, "user" + i, "user" + i + "@example.com");
        }
        Random r = new Random(2);
        List<Long> checked = new ArrayList<>();
        for (int i = 0; i < 20000; i++) {
            now.addAndGet(r.nextInt(5000));
            String login = r.nextInt(3) == 0 ? "nobody" + r.nextInt(100000) : spelling(r, "user" + r.nextInt(50));
            if (failFrom("198.51.100.7", login)) {
                checked.add(now.get());
            }
        }
        assertTrue(checked.size() > LoginThrottle.ADDRESS_FAILURES_LONG);
        assertAtMost("one address, short window", checked, WINDOW, LoginThrottle.ADDRESS_FAILURES);
        assertAtMost("one address, long window", checked, LoginThrottle.LONG_WINDOW_HOURS * 60 * MINUTE,
                LoginThrottle.ADDRESS_FAILURES_LONG);
    }

    @Test
    public void addressesInsideOneIpv6Slash56AreOneAddress() {
        Random r = new Random(3);
        int checked = 0;
        for (int i = 0; i < 1000; i++) {
            String address = String.format("2001:db8:1:%x:%x:%x:%x:%x", r.nextInt(256), r.nextInt(65536),
                    r.nextInt(65536), r.nextInt(65536), r.nextInt(65536));
            if (failFrom(address, "nobody" + i)) {
                checked++;
            }
        }
        assertEquals(LoginThrottle.ADDRESS_FAILURES, checked);
    }

    @Test
    public void aBurstOfConcurrentGuessesGetsNoMoreThanTheLimit() throws Exception {
        User admin = account(1, "admin", "admin@example.com");
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> all = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            final int n = i;
            all.add(pool.submit(() -> {
                go.await();
                if (throttle.begin("203.0.113." + (n % 200), admin, "admin", null).allowed()) {
                    allowed.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : all) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(LoginThrottle.ACCOUNT_FAILURES, allowed.get());
    }

    // ---- invariant 2: only an attempt's own success frees its slots -------------------------------------------------

    @Test
    public void someoneElsesSuccessesFromTheSameAddressFreeNothing() {
        account(1, "admin", "admin@example.com");
        User insider = account(2, "insider", "insider@example.com");
        Random r = new Random(4);
        List<Long> checked = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            now.addAndGet(r.nextInt(2000));
            if (r.nextInt(4) == 0) {
                LoginThrottle.Verdict v = throttle.begin("198.51.100.7", insider, "insider", null);
                if (v.allowed()) {
                    throttle.succeeded(v.attempt());   // the insider knows their own password
                }
            } else if (failFrom("198.51.100.7", "admin")) {
                checked.add(now.get());
            }
        }
        assertAtMost("guesses between someone's successes", checked, WINDOW, LoginThrottle.ADDRESS_FAILURES);
    }

    @Test
    public void aSuccessDoesNotCountAndARefusalCountsForNothing() {
        User a = account(1, "a", "a@example.com");
        for (int i = 0; i < 100; i++) {
            LoginThrottle.Verdict v = throttle.begin("203.0.113.7", a, "a", null);
            assertTrue(v.allowed());
            throttle.succeeded(v.attempt());
        }
        for (int i = 0; i < LoginThrottle.ADDRESS_FAILURES; i++) {
            failFrom("203.0.113.7", "nobody");
        }
        User b = account(3, "b", "b@example.com");
        for (int i = 0; i < 100; i++) {
            assertFalse(throttle.begin("203.0.113.7", b, "b", null).allowed());
        }
        // Those refusals (the address was locked) took nothing from b's own limit.
        int checked = 0;
        for (int i = 0; i < 40; i++) {
            if (throttle.begin(null, b, "b", null).allowed()) {
                checked++;
            }
        }
        assertEquals(LoginThrottle.ACCOUNT_FAILURES, checked);
    }

    @Test
    public void limitsLiftOnlyAsTheWindowSlides() {
        account(1, "admin", "admin@example.com");
        for (int i = 0; i < 40; i++) {
            failFrom("203.0.113." + i, "admin");
        }
        now.addAndGet(WINDOW - 1);
        assertFalse(failFrom("198.51.100.1", "admin"));
        now.addAndGet(1);
        assertTrue(failFrom("198.51.100.1", "admin"));
    }

    // ---- invariant 3: never open under memory pressure, memory bounded ---------------------------------------------

    @Test
    public void aFloodOfNewNamesAndAddressesNeverOpensTheLimitAndMemoryStaysBounded() {
        int cap = 50;
        LoginThrottle small = capped(cap);
        User admin = new User();
        admin.setId(1);
        Random r = new Random(5);
        List<Long> checkedForAdmin = new ArrayList<>();
        for (int i = 0; i < 30000; i++) {
            now.addAndGet(r.nextInt(50));
            // the flood: a new name from a new address every time, to fill every limiter
            small.begin("10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(256), null, "flood" + i, null);
            // the guesses start once the limiters are full, so the admin's account is a key that doesn't fit
            if (i > 1000 && i % 3 == 0
                    && small.begin("203.0." + r.nextInt(256) + "." + r.nextInt(256), admin, "admin", null).allowed()) {
                checkedForAdmin.add(now.get());
            }
            assertTrue("memory bounded", small.largestLimiter() <= cap + 1);
            assertTrue("name keys bounded", small.nameKeys() <= LoginThrottle.NAME_BUCKETS);
        }
        // The names go to their buckets, not the account limiter, so the admin's account keeps its own key and limit
        // (the flood of addresses fills the address limiter, whose overflow refuses most of these guesses anyway).
        assertAtMost("the admin's account under a flood", checkedForAdmin, WINDOW, LoginThrottle.ACCOUNT_FAILURES);
    }

    @Test
    public void anAccountThatDoesntFitGetsTheOverflowLimitNotNone() {
        LoginThrottle small = capped(50);
        for (int i = 0; i < 50; i++) {
            small.begin(null, user(1000 + i), "real" + i, null);   // 50 real accounts fill the account limiter
        }
        User admin = new User();
        admin.setId(1);
        int checked = 0;
        for (int i = 0; i < 1000; i++) {
            if (small.begin(null, admin, "admin", null).allowed()) {
                checked++;
            }
        }
        assertEquals(LoginThrottle.ACCOUNT_FAILURES * LoginThrottle.OVERFLOW_FACTOR, checked);
    }

    @Test
    public void anAccountAlreadyHeldKeepsItsOwnLimitDuringAFlood() {
        LoginThrottle small = capped(50);
        User admin = new User();
        admin.setId(1);
        assertTrue(small.begin(null, admin, "admin", null).allowed());   // the admin's key is held now
        for (int i = 0; i < 1000; i++) {
            small.begin(null, user(1000 + i), "real" + i, null);
        }
        int checked = 1;
        for (int i = 0; i < 100; i++) {
            if (small.begin(null, admin, "admin", null).allowed()) {
                checked++;
            }
        }
        assertEquals(LoginThrottle.ACCOUNT_FAILURES, checked);
    }

    // ---- invariant 4: unknown accounts are treated identically ----------------------------------------------------

    @Test
    public void aRealAccountAndAMissingOneGetTheSameAnswersToTheSameAttack() {
        account(1, "admin", "admin@example.com");
        LoginThrottle other = new LoginThrottle(now::get, SECRET);
        Random r = new Random(6);
        for (int i = 0; i < 5000; i++) {
            now.addAndGet(r.nextInt(3000));
            String address = "203.0." + r.nextInt(4) + "." + r.nextInt(8);
            boolean real = throttle.begin(address, resolve("admin"), "admin", null).allowed();
            boolean missing = other.begin(address, resolve("ghost"), "ghost", null).allowed();
            assertEquals("attempt " + i, real, missing);
        }
    }

    @Test
    public void caseVariantsOfAMissingNameShareAKeyAsTheyWouldForARealOne() {
        assertEquals(throttle.accountKey(null, "GhOsT"), throttle.accountKey(null, "ghost"));
        assertTrue(throttle.accountKey(null, "Ghost").startsWith("name#"));
        String longName = new String(new char[5000]).replace('\0', 'a');
        assertEquals("cut before it is hashed", throttle.accountKey(null, longName.substring(0,
                LoginThrottle.MAX_NAME_LENGTH)), throttle.accountKey(null, longName));
        assertEquals("user:7", throttle.accountKey(account(7, "x", "x@example.com"), "anything"));
    }

    @Test
    public void missingNamesFallIntoAFixedNumberOfBucketsPickedByTheInstallKey() {
        java.util.Set<String> buckets = new java.util.HashSet<>();
        int same = 0;
        LoginThrottle otherInstall = new LoginThrottle(now::get, SECRET.replace('0', '1'));
        for (int i = 0; i < 50000; i++) {
            String k = throttle.nameKey("nobody" + i);
            int n = Integer.parseInt(k.substring("name#".length()));
            assertTrue(k, n >= 0 && n < LoginThrottle.NAME_BUCKETS);
            buckets.add(k);
            if (k.equals(otherInstall.nameKey("nobody" + i))) {
                same++;
            }
        }
        assertEquals("names spread over every bucket", LoginThrottle.NAME_BUCKETS, buckets.size());
        assertTrue("another install's key picks other buckets (" + same + " of 50000 alike)", same < 100);
    }

    // ---- known browsers: an attacker can't lock the owner out -----------------------------------------------------

    private String knownBrowser(User user) {
        String header = throttle.knownBrowserCookie(user, false);
        assertTrue(header, header.startsWith(LoginThrottle.KNOWN_BROWSER_COOKIE + "="));
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }

    @Test
    public void theOwnersBrowserKeepsSigningInThroughASustainedAttack() {
        User admin = account(1, "admin", "admin@example.com");
        String cookie = knownBrowser(admin);
        Random r = new Random(7);
        int refusedNewBrowser = 0;
        for (int i = 0; i < 5000; i++) {
            now.addAndGet(r.nextInt(3000));
            failFrom("203.0." + r.nextInt(256) + "." + r.nextInt(256), spelling(r, "admin"));
            if (i % 50 == 49) {
                if (!throttle.begin("198.51.100.9", admin, "admin", null).allowed()) {
                    refusedNewBrowser++;
                }
                LoginThrottle.Verdict v = throttle.begin("198.51.100.1", admin, "admin", cookie);
                assertTrue("the owner's browser, attempt " + i, v.allowed());
                throttle.succeeded(v.attempt());
            }
        }
        assertTrue("the attack did lock new browsers out", refusedNewBrowser > 50);
    }

    @Test
    public void aKnownBrowserHasALimitOfItsOwnAndIsNoUseForAnotherAccount() {
        User admin = account(1, "admin", "admin@example.com");
        User mine = account(2, "me", "me@example.com");
        String adminsCookie = knownBrowser(admin);
        String myCookie = knownBrowser(mine);
        int checked = 0;
        for (int i = 0; i < 30; i++) {
            if (throttle.begin("198.51.100." + i, admin, "admin", adminsCookie).allowed()) {
                checked++;
            }
        }
        assertEquals("a stolen cookie is no free pass", LoginThrottle.KNOWN_BROWSER_FAILURES, checked);
        for (int i = 0; i < 40; i++) {
            failFrom("203.0.113." + i, "admin");
        }
        assertFalse("my cookie is no use against the admin",
                throttle.begin("198.51.100.200", admin, "admin", myCookie).allowed());
        String[] parts = myCookie.split("\\.");
        assertFalse("nor with the id changed", throttle.begin("198.51.100.200", admin, "admin",
                "1." + parts[1] + "." + parts[2] + "." + parts[3]).allowed());
        assertFalse(throttle.begin("198.51.100.200", admin, "admin", "garbage").allowed());
    }

    @Test
    public void aKnownBrowserCookieExpires() {
        User admin = account(1, "admin", "admin@example.com");
        String cookie = knownBrowser(admin);
        now.addAndGet(LoginThrottle.KNOWN_DAYS * 24 * 60 * MINUTE);
        for (int i = 0; i < 40; i++) {
            failFrom("203.0.113." + i, "admin");
        }
        assertFalse(throttle.begin("198.51.100.1", admin, "admin", cookie).allowed());
    }

    @Test
    public void theCookieIsHttpOnlyStrictAndOnlyForThePublicRoutes() {
        User admin = account(1, "admin", "admin@example.com");
        String header = throttle.knownBrowserCookie(admin, true);
        assertTrue(header, header.contains("; Path=/rest/public;"));
        assertTrue(header, header.contains("; HttpOnly"));
        assertTrue(header, header.contains("; SameSite=Strict"));
        assertTrue(header, header.endsWith("; Secure"));
        assertFalse(throttle.knownBrowserCookie(admin, false).contains("Secure"));
    }

    @Test
    public void aKnownBrowserCookieOutlivesARestartButNotARotatedSecret() {
        User admin = account(1, "admin", "admin@example.com");
        String cookie = knownBrowser(admin);
        // A restart: a new process, a new monotonic clock origin; the wall clock and jwt.secretkey carry on.
        AtomicLong monotonic = new AtomicLong(5);
        LoginThrottle restarted = new LoginThrottle(monotonic::get, now::get, "", false, LoginThrottle.MAX_KEYS, SECRET);
        assertTrue(restarted.keyFromSecret());
        assertTrue(restarted.knownBrowser(cookie, admin) != null);
        LoginThrottle rotated = new LoginThrottle(monotonic::get, now::get, "", false, LoginThrottle.MAX_KEYS,
                SECRET.replace('a', 'b'));
        assertNull("rotating jwt.secretkey makes every cookie unknown", rotated.knownBrowser(cookie, admin));
        // No usable secret: a key of the process only, so even the same setting doesn't carry a cookie over.
        LoginThrottle unset1 = new LoginThrottle(now::get, "");
        LoginThrottle unset2 = new LoginThrottle(now::get, "short");
        assertFalse(unset1.keyFromSecret());
        assertFalse(unset2.keyFromSecret());
        String c = unset1.knownBrowserCookie(admin, false);
        c = c.substring(c.indexOf('=') + 1, c.indexOf(';'));
        assertTrue(unset1.knownBrowser(c, admin) != null);
        assertNull(new LoginThrottle(now::get, "").knownBrowser(c, admin));
        // The install key is its own: not the secret itself, and not what another label would give.
        assertFalse(java.util.Arrays.equals(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                LoginThrottle.deriveKey(SECRET)));
        assertTrue(java.util.Arrays.equals(LoginThrottle.deriveKey(SECRET), LoginThrottle.deriveKey(" " + SECRET + " ")));
    }

    // ---- known addresses: the admin's own addresses keep signing in -------------------------------------------------

    /** A full sign-in to {@code user} from {@code address}: the right password, then signedIn. */
    private void signIn(LoginThrottle t, String address, User user) {
        LoginThrottle.Verdict v = t.begin(address, user, user.getLogin(), null);
        assertTrue("signs in from " + address, v.allowed());
        t.succeeded(v.attempt());
        t.signedIn(v.attempt(), user);
    }

    @Test
    public void knownAddressesAddToTheAccountLimitAtMostSixteenTimesTen() {
        // A known address's budget is in addition to the account limit, not in place of it: its failures don't use up
        // the account's. So an attacker holding every known address of an account gets 20 + 16 x 10 checks per window.
        User admin = account(1, "admin", "admin@example.com");
        for (int i = 0; i <= LoginThrottle.KNOWN_ADDRESSES_PER_ACCOUNT; i++) {
            signIn(throttle, "198.51.100." + i, admin);   // one more than are kept: the oldest is forgotten
        }
        int checked = 0;
        for (int i = 0; i <= LoginThrottle.KNOWN_ADDRESSES_PER_ACCOUNT; i++) {
            for (int n = 0; n < 3 * LoginThrottle.KNOWN_ADDRESS_FAILURES; n++) {
                if (throttle.begin("198.51.100." + i, admin, "admin", null).allowed()) {
                    checked++;
                }
            }
        }
        for (int i = 0; i < 10 * LoginThrottle.ACCOUNT_FAILURES; i++) {
            if (throttle.begin("203.0.113." + i, admin, "admin", null).allowed()) {
                checked++;
            }
        }
        assertEquals(LoginThrottle.ACCOUNT_FAILURES
                + LoginThrottle.KNOWN_ADDRESSES_PER_ACCOUNT * LoginThrottle.KNOWN_ADDRESS_FAILURES, checked);
        assertEquals(180, checked);
    }

    @Test
    public void anAddressThatSignedInHasALimitOfItsOwnForThatAccountOnly() {
        User admin = account(1, "admin", "admin@example.com");
        User other = account(2, "other", "other@example.com");
        signIn(throttle, "198.51.100.1", admin);
        for (int i = 0; i < 40; i++) {
            failFrom("203.0.113." + i, "admin");   // the account is locked for new addresses
        }
        assertFalse(throttle.begin("198.51.100.2", admin, "admin", null).allowed());
        int checked = 0;
        for (int i = 0; i < 30; i++) {
            if (throttle.begin("198.51.100.1", admin, "admin", null).allowed()) {
                checked++;
            }
        }
        assertEquals("a known address is no free pass", LoginThrottle.KNOWN_ADDRESS_FAILURES, checked);
        // The admin's address is no use against another account.
        for (int i = 0; i < 40; i++) {
            failFrom("203.0.112." + i, "other");
        }
        assertFalse(throttle.begin("198.51.100.1", other, "other", null).allowed());
        // Only a completed sign-in makes an address known: proving the password (succeeded) alone doesn't.
        now.addAndGet(WINDOW);   // the failures so far age out
        LoginThrottle.Verdict proven = throttle.begin("198.51.100.3", other, "other", null);
        assertTrue(proven.allowed());
        throttle.succeeded(proven.attempt());
        for (int i = 0; i < 40; i++) {
            failFrom("203.0.111." + i, "other");
        }
        assertFalse(throttle.begin("198.51.100.3", other, "other", null).allowed());
    }

    @Test
    public void aKnownAddressExpiresAndIsBoundedPerAccountAndInAll() {
        User admin = account(1, "admin", "admin@example.com");
        signIn(throttle, "198.51.100.1", admin);
        now.addAndGet(LoginThrottle.KNOWN_DAYS * 24 * 60 * MINUTE);
        assertFalse(throttle.isKnownAddress(1, "198.51.100.1"));

        for (int i = 0; i < LoginThrottle.KNOWN_ADDRESSES_PER_ACCOUNT + 5; i++) {
            now.addAndGet(1);
            signIn(throttle, "198.51.100." + (10 + i), admin);
        }
        assertEquals(LoginThrottle.KNOWN_ADDRESSES_PER_ACCOUNT, throttle.knownAddressCount());
        assertFalse("the oldest is forgotten first", throttle.isKnownAddress(1, "198.51.100.10"));
        assertTrue(throttle.isKnownAddress(1, "198.51.100." + (10 + LoginThrottle.KNOWN_ADDRESSES_PER_ACCOUNT + 4)));

        // Many accounts' sign-ins (each needs its password) can't grow the table past the cap; the account that signed
        // in least recently is forgotten first.
        int cap = 50;
        LoginThrottle small = capped(cap);
        for (int id = 1; id <= 100; id++) {
            User u = user(id);
            u.setLogin("u" + id);
            for (int a = 0; a < 3; a++) {
                now.addAndGet(1);
                signIn(small, "10.0." + id + "." + a, u);
            }
            assertTrue(small.knownAddressCount() <= cap);
        }
        assertTrue(small.isKnownAddress(100, "10.0.100.0"));
        assertFalse(small.isKnownAddress(1, "10.0.1.0"));
    }

    @Test
    public void aRefusalGivesTheSameRetryAfterWhicheverLimitRefused() {
        User admin = account(1, "admin", "admin@example.com");
        for (int i = 0; i < LoginThrottle.ADDRESS_FAILURES; i++) {
            failFrom("203.0.113.7", "nobody" + i);
        }
        LoginThrottle.Verdict byAddress = throttle.begin("203.0.113.7", admin, "admin", null);
        for (int i = 0; i < 40; i++) {
            failFrom("198.51.100." + i, "admin");
        }
        now.addAndGet(5 * MINUTE);
        LoginThrottle.Verdict byAccount = throttle.begin("192.0.2.1", admin, "admin", null);
        for (int i = 0; i < 30 * 5; i++) {
            now.addAndGet(WINDOW / 30);
            failFrom("203.0.113.9", "x" + i);
        }
        LoginThrottle.Verdict byLongAddress = throttle.begin("203.0.113.9", admin, "admin", null);
        assertFalse(byAddress.allowed());
        assertFalse(byAccount.allowed());
        assertFalse(byLongAddress.allowed());
        assertEquals(LoginThrottle.SHORT_WINDOW_MINUTES * 60L, byAddress.retryAfterSeconds());
        assertEquals(byAddress.retryAfterSeconds(), byAccount.retryAfterSeconds());
        assertEquals(byAddress.retryAfterSeconds(), byLongAddress.retryAfterSeconds());
    }

    // ---- addresses under the trusted-proxy rules ------------------------------------------------------------------

    private static HttpServletRequest request(String peer, String xff) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getRemoteAddr": return peer;
                        case "getHeader": return "X-Forwarded-For".equalsIgnoreCase((String) a[0]) ? xff : null;
                        case "getHeaders": return "X-Forwarded-For".equalsIgnoreCase((String) a[0]) && xff != null
                                ? Collections.enumeration(Collections.singletonList(xff))
                                : Collections.emptyEnumeration();
                        case "getCookies": return new Cookie[0];
                        default: return null;
                    }
                });
    }

    @Test
    public void aClientCantRotateItsAddressWithForwardedFor() {
        LoginThrottle docker = new LoginThrottle(now::get, now::get, "", true, LoginThrottle.MAX_KEYS, SECRET);
        LoginThrottle nativeInstall = new LoginThrottle(now::get, now::get, "", false, LoginThrottle.MAX_KEYS, SECRET);
        Random r = new Random(8);
        int publicPeer = 0;
        int sameHostProxy = 0;
        for (int i = 0; i < 200; i++) {
            String forged = "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(256) + ", 192.0.2."
                    + r.nextInt(256);
            if (docker.begin(request("203.0.113.7", forged), null, "n" + i).allowed()) {
                publicPeer++;   // a public peer is its own address, whatever it sends
            }
            if (nativeInstall.begin(request("127.0.0.1", "203.0.113.8"), null, "m" + i).allowed()) {
                sameHostProxy++;   // the proxy on this host names the client: one address
            }
        }
        assertEquals(LoginThrottle.ADDRESS_FAILURES, publicPeer);
        assertEquals(LoginThrottle.ADDRESS_FAILURES, sameHostProxy);
        // A LAN client that reaches a native install's Tomcat directly (no proxy) is limited by its own address,
        // whatever X-Forwarded-For it sends or leaves out.
        int lanClient = 0;
        for (int i = 0; i < 200; i++) {
            String forged = i % 2 == 0 ? null : "203.0.113." + r.nextInt(256);
            if (nativeInstall.begin(request("192.168.1.50", forged), null, "l" + i).allowed()) {
                lanClient++;
            }
        }
        assertEquals(LoginThrottle.ADDRESS_FAILURES, lanClient);
    }

    @Test
    public void aLanClientCantSprayAccountsBySendingForwardedFor() {
        // On a native install, a LAN client that isn't a listed proxy and forges a new X-Forwarded-For for each guess,
        // each guess at a different real account, still gets only its own address's budget.
        LoginThrottle nativeInstall = new LoginThrottle(now::get, now::get, "192.168.1.2", false,
                LoginThrottle.MAX_KEYS, SECRET);
        int checked = 0;
        for (int i = 0; i < 100; i++) {
            User u = account(100 + i, "user" + i, "user" + i + "@example.com");
            if (nativeInstall.begin(request("192.168.1.50", "198.51.100." + i), u, u.getLogin()).allowed()) {
                checked++;
            }
        }
        assertEquals(LoginThrottle.ADDRESS_FAILURES, checked);
    }

    @Test
    public void eachUnlistedPeerThatForwardsIsNamedInTheLogOnce() {
        // The first LAN client to send X-Forwarded-For must not use up the warning: the real unlisted proxy is named
        // too. Each peer once, and only so many in all, so a client can't flood the log.
        LoginThrottle nativeInstall = new LoginThrottle(now::get, now::get, "192.168.1.9", false,
                LoginThrottle.MAX_KEYS, SECRET);
        nativeInstall.begin(request("192.168.1.50", "203.0.113.1"), null, "a");
        nativeInstall.begin(request("192.168.1.50", "203.0.113.2"), null, "b");
        nativeInstall.begin(request("10.0.0.5", "198.51.100.4"), null, "c");
        nativeInstall.begin(request("192.168.1.9", "198.51.100.5"), null, "d");   // listed: believed, not warned about
        nativeInstall.begin(request("192.168.1.77", null), null, "e");            // no header: a client, not warned about
        nativeInstall.begin(request("203.0.113.7", "198.51.100.6"), null, "f");  // public: not a proxy of the install
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("192.168.1.50", "10.0.0.5")),
                nativeInstall.unlistedProxiesWarnedAbout());
        for (int i = 0; i < 3 * LoginThrottle.MAX_UNLISTED_PROXY_WARNINGS; i++) {
            nativeInstall.begin(request("10.1." + (i / 256) + "." + (i % 256), "198.51.100.4"), null, "n" + i);
        }
        assertEquals(LoginThrottle.MAX_UNLISTED_PROXY_WARNINGS, nativeInstall.unlistedProxiesWarnedAbout().size());
    }

    @Test
    public void addressKeys() {
        assertEquals("203.0.113.7", LoginThrottle.addressKey("203.0.113.7"));
        assertEquals(LoginThrottle.addressKey("2001:db8:1:200::1"), LoginThrottle.addressKey("2001:db8:1:2ff:ffff::1"));
        assertFalse(LoginThrottle.addressKey("2001:db8:1:200::1").equals(LoginThrottle.addressKey("2001:db8:1:300::1")));
        assertEquals("203.0.113.7", LoginThrottle.addressKey("::ffff:203.0.113.7"));
        assertNull(LoginThrottle.addressKey("not-an-address"));
        assertNull(LoginThrottle.addressKey(null));
        assertEquals("a scope is dropped, even for an interface this host doesn't have",
                LoginThrottle.addressKey("fe80::1"), LoginThrottle.addressKey("fe80::1%no-such-interface"));
        assertEquals(LoginThrottle.addressKey("fe80::1"), LoginThrottle.addressKey("fe80::1%3"));
        assertNull(LoginThrottle.addressKey("%eth0"));
    }
}
