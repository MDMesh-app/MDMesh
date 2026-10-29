/*
 * MDMesh: limits password guessing at sign-in, per client address and per account, without holding a thread.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.security;

import com.google.common.net.InetAddresses;
import com.hmdm.persistence.domain.User;
import com.hmdm.util.ClientAddress;
import com.hmdm.util.SecureTransport;
import com.hmdm.util.WindowLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * <p>Limits failed sign-ins. One instance (a Guice singleton) serves both sign-in routes, the console's
 * {@code /rest/public/auth/login} and the API's {@code /rest/public/jwt/login}, and the checks of the current password
 * on {@code /rest/private/users} (a password change, an e-mail change). All call
 * {@link #begin(HttpServletRequest, User, String)}, so they share every counter. One difference: the known-browser
 * cookie is sent only to {@code /rest/public} ({@link #COOKIE_PATH}), so a browser never sends it with a
 * current-password check, and that check takes the known-address or the address-and-account path below.</p>
 *
 * <h3>Threat model</h3>
 * <p>An unauthenticated attacker guesses passwords online. They pick the login string (any case, spelling, an e-mail
 * address instead of the login, names that don't exist), the endpoint, the timing (bursts of concurrent requests),
 * and every header; they may hold many addresses (IPv6 blocks, botnets); they may try to exhaust the limiter's memory,
 * or to lock a real admin out by failing sign-ins for that account. They may have an account of their own. They don't
 * know the install's secret ({@code jwt.secretkey}) or the admin's password (with it, nothing is left to guess).</p>
 *
 * <h3>Keys</h3>
 * <ul>
 *   <li><b>Account:</b> {@code user:<id>} when the login resolves to an account, exactly as sign-in resolves it (login,
 *       then e-mail, compared case-insensitively by the database), so every spelling that reaches the account shares one
 *       counter. A login that resolves to no account is counted in one of {@value #NAME_BUCKETS} buckets,
 *       {@code name#<n>}, picked by an HMAC of the login in lower case under the install key (below), so an attacker
 *       can't aim names at one bucket. Same limit and answer as an account: a refusal says nothing about whether an
 *       account exists (but see invariant 4).</li>
 *   <li><b>Address:</b> the client address under the trusted-proxy rules of {@link ClientAddress#forLimiting} (the
 *       last X-Forwarded-For entry, only from the install's own proxy; any other peer is itself, whatever it sends);
 *       IPv4 as is, IPv6 by its /56 (a household or a small site is one address). None only when a trusted proxy
 *       named no client; then only the account limits apply, and the first such sign-in logs a warning.</li>
 *   <li><b>Known browser:</b> {@code browser:<id>.<nonce>}, from a cookie ({@link #KNOWN_BROWSER_COOKIE}) that a full
 *       sign-in issues, on either route: HMAC-signed with the install key, naming one account,
 *       {@value #KNOWN_DAYS} days.</li>
 *   <li><b>Known address:</b> {@code known:<id>@<address>}, for an address (the address key above) that completed a
 *       sign-in to that account in the last {@value #KNOWN_DAYS} days: at most {@value #KNOWN_ADDRESSES_PER_ACCOUNT}
 *       per account (the oldest is forgotten first), and at most as many in all as one limiter holds keys (the
 *       accounts that signed in least recently are forgotten first). In memory: a restart forgets them.</li>
 * </ul>
 *
 * <h3>The install key</h3>
 * <p>HMAC-SHA256 under {@code jwt.secretkey} of the fixed label {@value #KEY_LABEL}: a key of its own, derived from the
 * install's API signing secret, which only the server holds (Docker keeps it in the data volume, native installs in
 * ROOT.xml) and which already guards everything (it signs API tokens). So known-browser cookies survive a restart,
 * and rotating {@code jwt.secretkey} makes every one of them unknown, together with every API token. Without a usable
 * secret (unset, or under {@value #MIN_SECRET_LENGTH} characters) the key is random per process, and a warning is
 * logged: a restart then makes every browser new. ({@code hash.secret} is not used: legacy Headwind devices hold
 * it.)</p>
 *
 * <h3>Limits</h3>
 * <p>Each attempt takes the path of the first of these that applies:</p>
 * <ol>
 *   <li><b>A valid known-browser cookie for the resolved account:</b> per browser {@value #KNOWN_BROWSER_FAILURES} in
 *       {@value #SHORT_WINDOW_MINUTES} minutes, and nothing else.</li>
 *   <li><b>A known address for the resolved account:</b> per (account, address) {@value #KNOWN_ADDRESS_FAILURES} in
 *       {@value #SHORT_WINDOW_MINUTES} minutes, and nothing else: these failures don't count toward the account limit
 *       below, so this budget is in addition to it.</li>
 *   <li><b>Otherwise:</b> per address {@value #ADDRESS_FAILURES} in {@value #SHORT_WINDOW_MINUTES} minutes and
 *       {@value #ADDRESS_FAILURES_LONG} in {@value #LONG_WINDOW_HOURS} hours (the backoff for a patient source), and per
 *       account (or name bucket) {@value #ACCOUNT_FAILURES} in {@value #SHORT_WINDOW_MINUTES} minutes, from all
 *       addresses together. The account limit is what stops a guesser with many addresses. It is a hard limit, which
 *       the right password doesn't pass either: one that a clean address could pass would give a botnet a few checks
 *       per address.</li>
 * </ol>
 * <p>So an attacker failing sign-ins for an admin's account locks out only browsers and addresses new to that
 * account: the admin's browser (the console, or an API client that keeps cookies) and the addresses the admin signed
 * in from in the last {@value #KNOWN_DAYS} days (an office, a home, the API client's host) keep signing in. The known
 * paths skip the address limits, so a flood of addresses that fills the address limiter, or a misconfigured proxy that
 * makes every client one address, doesn't lock them out either. The price: during a sustained attack on an account, a
 * browser that never signed in to it, from an address that never did, waits until the attack has stopped for
 * {@value #SHORT_WINDOW_MINUTES} minutes, or the server restarts (which clears every counter).</p>
 * <p>Neither known path is open to the attacker. A cookie can't be forged (HMAC under the install key), names its
 * account (so a cookie for one's own account is no use against another), and has its own limit (so a stolen one is no
 * free pass). An address becomes known to an account only by a full sign-in to that account, with its password, from
 * that address. What the attacker gains from an address the admin really signed in from (a shared NAT, a /56
 * neighbour): {@value #KNOWN_ADDRESS_FAILURES} checks per {@value #SHORT_WINDOW_MINUTES} minutes against that one
 * account, in addition to the account limit, not in place of it. So an account gets at most
 * {@value #ACCOUNT_FAILURES} checked guesses per {@value #SHORT_WINDOW_MINUTES} minutes, plus
 * {@value #KNOWN_ADDRESS_FAILURES} for each of its known addresses the attacker sends from: with
 * {@value #KNOWN_ADDRESSES_PER_ACCOUNT} known addresses at most, 180 if the attacker were at every one (and
 * {@value #KNOWN_BROWSER_FAILURES} more per stolen known-browser cookie).</p>
 *
 * <h3>Invariants</h3>
 * <ol>
 *   <li><b>Counted before checked.</b> An attempt takes a slot in every limit on its path before its password is
 *       checked, atomically per limit; if any limit refuses, it gives back what it took and its password is never
 *       checked. So no burst, interleaving or endpoint mix gets more checked guesses than the limits.</li>
 *   <li><b>Only its own success frees a slot.</b> An attempt is a failure unless the right password is proven, and then
 *       exactly the slots that attempt took are given back ({@link #succeeded}), once: each slot is held under the
 *       unique id of its grant ({@link WindowLimiter}), so a second {@code succeeded} gives back nothing. Nothing else
 *       resets or frees a counter: not another key's success, not a different endpoint, not an exception (an error
 *       while checking counts as a failure). Slots otherwise expire with their window, on a monotonic clock.</li>
 *   <li><b>Never open under memory pressure.</b> The address, account, browser and known-address limiters each hold
 *       at most {@value #MAX_KEYS} keys plus one: a key that doesn't fit is counted in that limiter's single shared
 *       overflow key, a coarse global limit, so memory stays bounded and a flood of new addresses can make sign-in
 *       slower for keys that don't fit, never unlimited. Keys already held keep their own limits. Names that resolve
 *       to no account can't flood anything: there are only {@value #NAME_BUCKETS} name keys, in a limiter of their
 *       own, so they never fill the account limiter, never push an account into its overflow, and never lock a real
 *       account. The known-address table is bounded as above.</li>
 *   <li><b>Unknown accounts are treated alike:</b> same limit, path and answer as real ones, and with no flood an
 *       unknown name is refused after exactly the {@value #ACCOUNT_FAILURES} attempts a real account is. What is left:
 *       (a) a bucket is shared, so an unknown name is refused sooner when other unknown names in its bucket failed in
 *       the same window. Telling names apart that way takes failing about {@value #NAME_BUCKETS} &times;
 *       {@value #ACCOUNT_FAILURES} sign-ins for unknown names in one window (from some 8,000 addresses, given the
 *       address limit); then a real account still has its own {@value #ACCOUNT_FAILURES} while most unknown names
 *       have none. (b) Spellings of one real account share a counter, which takes about {@value #ACCOUNT_FAILURES}
 *       failures from several addresses, and locks that account, to observe. (c) From an address known to an account,
 *       failures for it don't use up the address's own budget while failures for a missing name do: observable only
 *       from an address the account's owner signed in from.</li>
 *   <li><b>A refusal looks the same whichever limit refused:</b> callers give one message, and
 *       {@link Verdict#retryAfterSeconds()} is always the full short window, {@value #SHORT_WINDOW_MINUTES}
 *       minutes.</li>
 * </ol>
 *
 * <h3>Before the limit</h3>
 * <p>The callers resolve the login to an account before {@link #begin}: every attempt, refused or not, costs the
 * database one indexed lookup of the login, and a second one of the e-mail when the login matches no login (an e-mail
 * address or a missing name). A refused attempt then answers at once, without checking the password; an allowed one
 * for an account checks one SHA-1 of the sent MD5 (microseconds, with the built-in authentication), while one for a
 * missing name checks nothing. So the limiter doesn't shield the database from a flood of requests (a rate limit at
 * the proxy does), and the lookups and the hash leave a small timing difference between a login, an e-mail and a
 * missing name; the limit itself answers them alike.</p>
 *
 * <p>The counters and the known addresses are in memory, per server process: a restart clears them (the operator's
 * way out of a lockout). Known-browser cookies outlive a restart.</p>
 */
@Singleton
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    static final int SHORT_WINDOW_MINUTES = 15;
    static final int LONG_WINDOW_HOURS = 6;
    static final int ADDRESS_FAILURES = 10;
    static final int ADDRESS_FAILURES_LONG = 50;
    static final int ACCOUNT_FAILURES = 20;
    static final int KNOWN_BROWSER_FAILURES = 10;
    static final int KNOWN_ADDRESS_FAILURES = 10;
    static final int MAX_KEYS = 20000;
    /** The overflow key's limit, as a multiple of the per-key limit: coarse, global, but a limit. */
    static final int OVERFLOW_FACTOR = 5;
    /** The keys that names resolving to no account are counted in. */
    static final int NAME_BUCKETS = 4096;
    /** Longer names are cut before they are hashed: the bucket only has to look random, not read a megabyte. */
    static final int MAX_NAME_LENGTH = 256;
    static final int KNOWN_ADDRESSES_PER_ACCOUNT = 16;
    /** How long a known browser's cookie, and a known address, last. */
    static final long KNOWN_DAYS = 30;
    /** A shorter {@code jwt.secretkey} isn't used for the install key. */
    static final int MIN_SECRET_LENGTH = 32;
    /** The HMAC label that derives the install key from {@code jwt.secretkey}. */
    static final String KEY_LABEL = "MDMesh sign-in limit v1";
    /** How many unlisted peers that send X-Forwarded-For are named in the log, each once: a LAN client can send it too. */
    static final int MAX_UNLISTED_PROXY_WARNINGS = 32;

    /** The known-browser cookie: sent only to the public routes, both sign-in routes among them. */
    public static final String KNOWN_BROWSER_COOKIE = "MDMESH_SIGNIN";
    static final String COOKIE_PATH = "/rest/public";

    /** Context parameter: 1 when every private-network peer is the install's own proxy (Docker sets it). */
    public static final String PRIVATE_PEERS_ARE_PROXIES = "proxy.private.networks";
    /** Context parameter the install key is derived from. */
    public static final String SECRET_PARAMETER = "jwt.secretkey";

    private static final long SHORT_WINDOW_MS = SHORT_WINDOW_MINUTES * 60L * 1000L;
    private static final long LONG_WINDOW_MS = LONG_WINDOW_HOURS * 60L * 60L * 1000L;
    private static final long KNOWN_MS = KNOWN_DAYS * 24L * 60L * 60L * 1000L;
    /** A cookie issued up to this far in the wall clock's future still counts (the clock was set back). */
    private static final long CLOCK_SKEW_MS = 24L * 60L * 60L * 1000L;

    private final LongSupplier clock;
    private final LongSupplier wallClock;
    private final List<String> trustedProxies;
    private final boolean privatePeersAreProxies;
    private final int maxKnown;
    private final WindowLimiter addresses;
    private final WindowLimiter addressesLong;
    private final WindowLimiter accounts;
    private final WindowLimiter names;
    private final WindowLimiter browsers;
    private final WindowLimiter knownAddresses;
    private final byte[] key;
    private final boolean keyFromSecret;
    private final boolean secureCookie;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean warnedNoClient = new AtomicBoolean();
    /** The unlisted private peers that sent X-Forwarded-For and have been named in the log, at most as many as below. */
    private final java.util.Set<String> warnedUnlistedProxies = new java.util.HashSet<>();

    /** Per account id, its known addresses and when each last signed in, oldest first; accounts oldest first. */
    private final LinkedHashMap<Integer, LinkedHashMap<String, Long>> known = new LinkedHashMap<>();
    private int knownCount;

    /**
     * @param proxyAddresses         {@code proxy.addresses}: comma-separated addresses of proxies whose
     *                               X-Forwarded-For is believed (loopback always is)
     * @param privatePeersAreProxies {@value #PRIVATE_PEERS_ARE_PROXIES}: believe it from any private-network peer
     * @param installSecret          {@value #SECRET_PARAMETER}, which the install key is derived from
     * @param baseUrl                {@code base.url}: an https one makes the known-browser cookie Secure
     */
    @Inject
    public LoginThrottle(@Named("proxy.addresses") String proxyAddresses,
                         @Named(PRIVATE_PEERS_ARE_PROXIES) boolean privatePeersAreProxies,
                         @Named(SECRET_PARAMETER) String installSecret,
                         @Named(SecureTransport.BASE_URL_PARAMETER) String baseUrl) {
        this(WindowLimiter.MONOTONIC, System::currentTimeMillis, proxyAddresses, privatePeersAreProxies, MAX_KEYS,
                installSecret, SecureTransport.isHttps(baseUrl));
    }

    /** One clock for the counters and the cookies, no proxies. */
    LoginThrottle(LongSupplier clock, String installSecret) {
        this(clock, clock, "", false, MAX_KEYS, installSecret, false);
    }

    LoginThrottle(LongSupplier clock, LongSupplier wallClock, String proxyAddresses, boolean privatePeersAreProxies,
                  int maxKeys, String installSecret) {
        this(clock, wallClock, proxyAddresses, privatePeersAreProxies, maxKeys, installSecret, false);
    }

    /**
     * @param clock     the counters' clock, monotonic
     * @param wallClock the cookies' clock: the time of day, which a cookie must still read right after a restart
     */
    LoginThrottle(LongSupplier clock, LongSupplier wallClock, String proxyAddresses, boolean privatePeersAreProxies,
                  int maxKeys, String installSecret, boolean secureCookie) {
        this.clock = clock;
        this.secureCookie = secureCookie;
        this.wallClock = wallClock;
        List<String> proxies = new ArrayList<>();
        for (String p : (proxyAddresses == null ? "" : proxyAddresses).split(",")) {
            if (!p.trim().isEmpty()) {
                proxies.add(p.trim());
            }
        }
        this.trustedProxies = Collections.unmodifiableList(proxies);
        this.privatePeersAreProxies = privatePeersAreProxies;
        this.maxKnown = maxKeys;
        this.addresses = limiter(SHORT_WINDOW_MS, ADDRESS_FAILURES, maxKeys);
        this.addressesLong = limiter(LONG_WINDOW_MS, ADDRESS_FAILURES_LONG, maxKeys);
        this.accounts = limiter(SHORT_WINDOW_MS, ACCOUNT_FAILURES, maxKeys);
        this.names = new WindowLimiter(clock, SHORT_WINDOW_MS, ACCOUNT_FAILURES, NAME_BUCKETS, 0);
        this.browsers = limiter(SHORT_WINDOW_MS, KNOWN_BROWSER_FAILURES, maxKeys);
        this.knownAddresses = limiter(SHORT_WINDOW_MS, KNOWN_ADDRESS_FAILURES, maxKeys);
        byte[] derived = deriveKey(installSecret);
        this.keyFromSecret = derived != null;
        if (derived == null) {
            derived = new byte[32];
            random.nextBytes(derived);
            log.warn("{} is not set (or is shorter than {} characters): the sign-in limit signs known-browser "
                    + "cookies with a key of this process only, so after a restart every browser and API client is new "
                    + "to it (docs/OPERATIONS.md, Sign-in limits)", SECRET_PARAMETER, MIN_SECRET_LENGTH);
        }
        this.key = derived;
    }

    private WindowLimiter limiter(long windowMs, int max, int maxKeys) {
        return new WindowLimiter(clock, windowMs, max, maxKeys, max * OVERFLOW_FACTOR);
    }

    /** HMAC-SHA256(secret, {@value #KEY_LABEL}), or null when the secret is missing or too short to be one. */
    static byte[] deriveKey(String secret) {
        String s = secret == null ? "" : secret.trim();
        if (s.length() < MIN_SECRET_LENGTH) {
            return null;
        }
        return hmac(s.getBytes(StandardCharsets.UTF_8), KEY_LABEL);
    }

    /** True when the install key comes from the install's secret (so cookies outlive a restart). */
    boolean keyFromSecret() {
        return keyFromSecret;
    }

    /** The most keys any capped limiter holds right now (memory stays bounded). */
    int largestLimiter() {
        return Math.max(Math.max(Math.max(addresses.size(), addressesLong.size()),
                Math.max(accounts.size(), browsers.size())), knownAddresses.size());
    }

    /** The name keys held right now: never more than {@value #NAME_BUCKETS}. */
    int nameKeys() {
        return names.size();
    }

    /** The known addresses held right now, all accounts together. */
    synchronized int knownAddressCount() {
        return knownCount;
    }

    /** One sign-in attempt that was let through: the slots it took, and whom and where it was for. */
    public static final class Attempt {
        private final List<Taken> taken;
        private final Integer userId;
        private final String address;

        private Attempt(List<Taken> taken, Integer userId, String address) {
            this.taken = taken;
            this.userId = userId;
            this.address = address;
        }
    }

    /** What {@link #begin} decided: the attempt may go on, or it is refused for a while. */
    public static final class Verdict {
        private static final Verdict REFUSED = new Verdict(null);
        private final Attempt attempt;

        private Verdict(Attempt attempt) {
            this.attempt = attempt;
        }

        /** True when the attempt may go on: check the password, then report a success with {@link #succeeded}. */
        public boolean allowed() {
            return attempt != null;
        }

        public Attempt attempt() {
            return attempt;
        }

        /**
         * For a refused attempt, the Retry-After to give: always the full short window, whichever limit refused, so
         * the answer doesn't tell an address limit from an account limit. It is advice: the address's longer limit
         * ({@value #LONG_WINDOW_HOURS} hours) may still refuse then.
         */
        public long retryAfterSeconds() {
            return SHORT_WINDOW_MS / 1000;
        }
    }

    private static final class Taken {
        final WindowLimiter limiter;
        final WindowLimiter.Grant grant;

        Taken(WindowLimiter limiter, WindowLimiter.Grant grant) {
            this.limiter = limiter;
            this.grant = grant;
        }
    }

    /** Starts a sign-in attempt for a request: its address under the proxy rules, and its known-browser cookie. */
    public Verdict begin(HttpServletRequest request, User user, String login) {
        String address = ClientAddress.forLimiting(request, trustedProxies, privatePeersAreProxies);
        warnIfClientsLookAlike(request, address);
        return begin(address, user, login, cookie(request));
    }

    /**
     * Starts a sign-in attempt, before the password is checked. It is a failure unless {@link #succeeded} is called
     * for it.
     *
     * @param clientAddress the client's address, or null when it can't be told apart from others'
     * @param user          the account the login resolves to, or null
     * @param login         the login as sent
     * @param browserCookie the {@link #KNOWN_BROWSER_COOKIE} value sent, or null
     */
    public Verdict begin(String clientAddress, User user, String login, String browserCookie) {
        String address = addressKey(clientAddress);
        Integer id = user == null ? null : user.getId();
        List<WindowLimiter> limiters = new ArrayList<>(3);
        List<String> keys = new ArrayList<>(3);
        String browser = id == null ? null : knownBrowser(browserCookie, user);
        if (browser != null) {
            limiters.add(browsers);
            keys.add(browser);
        } else if (id != null && address != null && isKnownAddress(id, address)) {
            limiters.add(knownAddresses);
            keys.add("known:" + id + "@" + address);
        } else {
            if (address != null) {
                limiters.add(addresses);
                keys.add(address);
                limiters.add(addressesLong);
                keys.add(address);
            }
            if (id != null) {
                limiters.add(accounts);
                keys.add("user:" + id);
            } else {
                limiters.add(names);
                keys.add(nameKey(login));
            }
        }

        List<Taken> taken = new ArrayList<>(limiters.size());
        for (int i = 0; i < limiters.size(); i++) {
            WindowLimiter limiter = limiters.get(i);
            WindowLimiter.Grant grant = limiter.acquire(keys.get(i));
            if (!grant.granted()) {
                succeeded(new Attempt(taken, null, null));   // a refused attempt counts for nothing: give it back
                return Verdict.REFUSED;
            }
            taken.add(new Taken(limiter, grant));
        }
        return new Verdict(new Attempt(taken, id, address));
    }

    /** The attempt proved the right password: give back exactly the slots it took, and nothing else. */
    public void succeeded(Attempt attempt) {
        if (attempt != null) {
            for (Taken t : attempt.taken) {
                t.limiter.release(t.grant);
            }
        }
    }

    /**
     * A full sign-in completed for {@code user} on this attempt: the right password, and nothing further pending.
     * (MDMesh has no second-factor step today: a sign-in whose customer requires one never completes, so it must not
     * call this.) The attempt's address becomes known to the account, and the answer is the {@code Set-Cookie} value
     * that makes this browser or API client known to it: HttpOnly, SameSite=Strict, sent only to
     * {@value #COOKIE_PATH}, {@value #KNOWN_DAYS} days, and Secure when {@code base.url} is https.
     */
    public String signedIn(Attempt attempt, User user) {
        if (attempt != null && attempt.address != null && user.getId() != null && user.getId().equals(attempt.userId)) {
            rememberAddress(user.getId(), attempt.address);
        }
        return knownBrowserCookie(user, secureCookie);
    }

    /** The known-browser {@code Set-Cookie} value for {@code user} (see {@link #signedIn}). */
    String knownBrowserCookie(User user, boolean secure) {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String body = user.getId() + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) + "."
                + wallClock.getAsLong();
        return KNOWN_BROWSER_COOKIE + "=" + body + "." + mac(body) + "; Path=" + COOKIE_PATH + "; Max-Age="
                + (KNOWN_MS / 1000) + "; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : "");
    }

    /** The browser key when {@code cookie} is a genuine, unexpired known-browser cookie for {@code user}, else null. */
    String knownBrowser(String cookie, User user) {
        if (cookie == null || cookie.length() > 200) {
            return null;
        }
        int lastDot = cookie.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String body = cookie.substring(0, lastDot);
        if (!MessageDigest.isEqual(mac(body).getBytes(StandardCharsets.US_ASCII),
                cookie.substring(lastDot + 1).getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        String[] parts = body.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals(String.valueOf(user.getId()))) {
            return null;
        }
        long issued;
        try {
            issued = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        long age = wallClock.getAsLong() - issued;
        return age > -CLOCK_SKEW_MS && age < KNOWN_MS ? "browser:" + parts[0] + "." + parts[1] : null;
    }

    private static String cookie(HttpServletRequest request) {
        Cookie[] cookies = request == null ? null : request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (c != null && KNOWN_BROWSER_COOKIE.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    // ---- known addresses ---------------------------------------------------------------------------------------------

    /** True when {@code address} completed a sign-in to account {@code id} within the last {@value #KNOWN_DAYS} days. */
    synchronized boolean isKnownAddress(int id, String address) {
        LinkedHashMap<String, Long> mine = known.get(id);
        Long at = mine == null ? null : mine.get(address);
        return at != null && clock.getAsLong() - at < KNOWN_MS;
    }

    /** Remembers a full sign-in to account {@code id} from {@code address}, within the bounds. */
    private synchronized void rememberAddress(int id, String address) {
        long now = clock.getAsLong();
        LinkedHashMap<String, Long> mine = known.remove(id);
        if (mine == null) {
            mine = new LinkedHashMap<>();
        } else {
            knownCount -= mine.size();
        }
        mine.remove(address);
        mine.put(address, now);   // newest last
        for (Iterator<Long> it = mine.values().iterator(); it.hasNext(); ) {
            long at = it.next();
            if (mine.size() > KNOWN_ADDRESSES_PER_ACCOUNT || now - at >= KNOWN_MS) {
                it.remove();
            } else {
                break;
            }
        }
        known.put(id, mine);   // the account that signed in most recently, last
        knownCount += mine.size();
        // Over the global cap, or wholly expired: forget the accounts that signed in least recently.
        for (Iterator<Map.Entry<Integer, LinkedHashMap<String, Long>>> it = known.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<Integer, LinkedHashMap<String, Long>> e = it.next();
            if (e.getKey() == id) {
                break;
            }
            if (knownCount > maxKnown || now - newest(e.getValue()) >= KNOWN_MS) {
                knownCount -= e.getValue().size();
                it.remove();
            } else {
                break;
            }
        }
    }

    private static long newest(LinkedHashMap<String, Long> addresses) {
        long newest = Long.MIN_VALUE;
        for (long at : addresses.values()) {
            newest = at;
        }
        return newest;
    }

    // ---- keys --------------------------------------------------------------------------------------------------------

    /**
     * The key a login that resolves to no account is counted under: {@code name#<n>}, n from an HMAC of the login in
     * lower case (as the database compares logins, {@code lower(login) = lower(?)}, so two spellings that would reach
     * the same account if it existed share a bucket), cut to {@value #MAX_NAME_LENGTH} characters.
     */
    String nameKey(String login) {
        String name = login == null ? "" : login.toLowerCase(Locale.ROOT);
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        byte[] h = hmac(key, "name\u0000" + name);
        int n = ((h[0] & 0xff) << 24) | ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
        return "name#" + Math.floorMod(n, NAME_BUCKETS);
    }

    /** {@code user:<id>} for an account, else {@link #nameKey}. */
    String accountKey(User user, String login) {
        return user != null && user.getId() != null ? "user:" + user.getId() : nameKey(login);
    }

    private String mac(String body) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, "browser\u0000" + body));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256", e);
        }
    }

    /**
     * The address itself for IPv4, its /56 for IPv6, without an IPv6 scope ({@code %eth0}: Guava would look the
     * interface up, and throw for one this host doesn't have); null for no address (or one that isn't an IP literal).
     */
    static String addressKey(String address) {
        if (address == null) {
            return null;
        }
        String literal = address.trim();
        int scope = literal.indexOf('%');
        if (scope >= 0) {
            literal = literal.substring(0, scope);
        }
        if (!InetAddresses.isInetAddress(literal)) {
            return null;
        }
        InetAddress a = InetAddresses.forString(literal);
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            for (int i = 7; i < 16; i++) {
                b[i] = 0;
            }
            try {
                return InetAddress.getByAddress(b).getHostAddress() + "/56";
            } catch (java.net.UnknownHostException e) {
                return null;
            }
        }
        return a.getHostAddress();
    }

    // ---- telling clients apart -----------------------------------------------------------------------------------------

    /**
     * Logs the two ways an install can fail to tell its clients apart at sign-in: a trusted proxy that names no client
     * (the per-address limit can't apply; logged once), and a private-network peer that sends X-Forwarded-For but isn't
     * a trusted proxy (if it is a proxy, every client behind it shares its address's limit). The second is logged once
     * per peer, for up to {@value #MAX_UNLISTED_PROXY_WARNINGS} peers, so a LAN client that sends the header first
     * doesn't keep the real proxy from being named, and can't flood the log either.
     */
    private void warnIfClientsLookAlike(HttpServletRequest request, String address) {
        String peer = request == null ? null : request.getRemoteAddr();
        if (peer == null || !InetAddresses.isInetAddress(peer)) {
            return;
        }
        if (address == null) {
            if (!warnedNoClient.getAndSet(true)) {
                log.warn("A sign-in came through the proxy at {} with no X-Forwarded-For naming the client, so the "
                        + "per-address sign-in limit can't apply to such requests, only the per-account limits. Make "
                        + "that proxy set X-Forwarded-For (docs/OPERATIONS.md, Sign-in limits)", peer);
            }
        } else if (!privatePeersAreProxies && address.equals(peer) && !trustedProxies.contains(peer)
                && ClientAddress.isLocalOrPrivate(peer) && !InetAddresses.forString(peer).isLoopbackAddress()
                && request.getHeader("X-Forwarded-For") != null && firstWarningAbout(peer)) {
            log.warn("A sign-in from {} carried X-Forwarded-For, but {} is not a trusted proxy, so the header is "
                    + "ignored and {} is limited as one client. If it is your proxy, add it to proxy.addresses and "
                    + "restart: until then every client behind it shares one sign-in limit, and 10 failed sign-ins "
                    + "refuse every new browser for 15 minutes (docs/OPERATIONS.md, Sign-in limits)", peer, peer, peer);
        }
    }
    /** True once per peer, while fewer than {@value #MAX_UNLISTED_PROXY_WARNINGS} peers have been named. */
    private boolean firstWarningAbout(String peer) {
        synchronized (warnedUnlistedProxies) {
            return warnedUnlistedProxies.size() < MAX_UNLISTED_PROXY_WARNINGS && warnedUnlistedProxies.add(peer);
        }
    }

    /** The unlisted peers named in the log so far. */
    java.util.Set<String> unlistedProxiesWarnedAbout() {
        synchronized (warnedUnlistedProxies) {
            return new java.util.HashSet<>(warnedUnlistedProxies);
        }
    }
}
