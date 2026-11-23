package com.hmdm.util;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.junit.Test;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Stored password formats: bcrypt (what is written now) and the legacy salted SHA-1 (still accepted), plus the
 * SecureRandom provenance of the token generators. The source-level guard against weak generators anywhere in
 * production code is {@link WeakRandomGuardTest}; this test pins the runtime type of the two fields it allow-lists.
 */
public class PasswordUtilTest {

    /** MD5("password"), as the console sends it. */
    private static final String MD5 = "5F4DCC3B5AA765D61D8327DEB882CF99";
    private static final String OTHER_MD5 = "0123456789ABCDEF0123456789ABCDEF";

    /** What 0.4.x stored: SHA-1(input + "5YdSYHyg2U"), computed here independently of PasswordUtil. */
    private static String legacy(String input) {
        return CryptoUtil.getSHA1String(input + "5YdSYHyg2U");
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    @Test
    public void tokenGeneratorsUseSecureRandom() throws Exception {
        Field f = PasswordUtil.class.getDeclaredField("random");
        f.setAccessible(true);
        assertTrue(f.get(null) instanceof SecureRandom);
        Field c = CryptoUtil.class.getDeclaredField("RANDOM");
        c.setAccessible(true);
        assertTrue(c.get(null) instanceof SecureRandom);
    }

    @Test
    public void newHashesAreBcryptWithTheCurrentCostAndARandomSalt() {
        String a = PasswordUtil.getHashFromMd5(MD5);
        String b = PasswordUtil.getHashFromMd5(MD5);
        assertTrue(a, a.startsWith("$2a$" + PasswordUtil.BCRYPT_COST + "$"));
        assertEquals(60, a.length());
        assertNotEquals("a random salt each time", a, b);
        assertTrue(PasswordUtil.isBcrypt(a));
        assertFalse(PasswordUtil.needsRehash(a));
        assertTrue(PasswordUtil.passwordMatch(MD5, a));
        assertTrue(PasswordUtil.passwordMatch(MD5, b));
    }

    @Test
    public void aBcryptHashMatchesTheMd5InEitherCaseAndNothingElse() {
        String stored = PasswordUtil.getHashFromMd5(MD5.toLowerCase(Locale.ROOT));
        assertTrue(PasswordUtil.passwordMatch(MD5, stored));
        assertTrue(PasswordUtil.passwordMatch(MD5.toLowerCase(Locale.ROOT), stored));
        assertFalse("wrong password", PasswordUtil.passwordMatch(OTHER_MD5, stored));
        assertFalse(PasswordUtil.passwordMatch("password", stored));
        assertFalse(PasswordUtil.passwordMatch("", stored));
        assertFalse(PasswordUtil.passwordMatch(null, stored));
        assertFalse("the stored value itself is no password", PasswordUtil.passwordMatch(stored, stored));
    }

    @Test
    public void getHashFromRawIsTheHashOfWhatAClientSends() {
        assertEquals(MD5, CryptoUtil.getMD5String("password"));
        String stored = PasswordUtil.getHashFromRaw("password");
        assertTrue(PasswordUtil.isBcrypt(stored));
        assertTrue(PasswordUtil.passwordMatch(MD5, stored));
    }

    @Test
    public void onlyAnMd5CanBeStored() {
        for (String bad : new String[]{null, "", "password", MD5 + "0", MD5.substring(1),
                "Z" + MD5.substring(1), MD5.substring(0, 31) + " "}) {
            try {
                PasswordUtil.getHashFromMd5(bad);
                fail("stored: " + bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
            assertFalse(bad, PasswordUtil.isMd5Hex(bad));
        }
        assertTrue(PasswordUtil.isMd5Hex(MD5));
        assertTrue(PasswordUtil.isMd5Hex(MD5.toLowerCase(Locale.ROOT)));
    }

    @Test
    public void aLegacyHashStillMatchesExactlyWhatWasSent() {
        String stored = legacy(MD5);
        assertEquals(40, stored.length());
        assertFalse(PasswordUtil.isBcrypt(stored));
        assertTrue(PasswordUtil.passwordMatch(MD5, stored));
        assertTrue("stored in lower case (install/lib/db.sh's sha1sum)",
                PasswordUtil.passwordMatch(MD5, stored.toLowerCase(Locale.ROOT)));
        assertFalse("the input is compared as sent, as 0.4.x did",
                PasswordUtil.passwordMatch(MD5.toLowerCase(Locale.ROOT), stored));
        assertFalse("wrong password", PasswordUtil.passwordMatch(OTHER_MD5, stored));
        assertFalse(PasswordUtil.passwordMatch(null, stored));
        assertFalse(PasswordUtil.passwordMatch(stored, stored));
        // 0.4.x's API took any string as a password: such an account still signs in
        assertTrue(PasswordUtil.passwordMatch("plain text", legacy("plain text")));
    }

    @Test
    public void legacyAndLowCostHashesNeedARehash() {
        assertTrue(PasswordUtil.needsRehash(legacy(MD5)));
        String lowCost = BCrypt.withDefaults().hashToString(4, MD5.toCharArray());
        assertTrue(PasswordUtil.passwordMatch(MD5, lowCost));
        assertTrue(PasswordUtil.needsRehash(lowCost));
        assertTrue(PasswordUtil.needsRehash(null));
        assertTrue(PasswordUtil.needsRehash("$2a$"));
        assertTrue(PasswordUtil.needsRehash("$2a$xx$"));
    }

    @Test
    public void otherBcryptVersionsAreAccepted() {
        for (BCrypt.Version v : new BCrypt.Version[]{BCrypt.Version.VERSION_2B, BCrypt.Version.VERSION_2Y}) {
            String stored = BCrypt.with(v).hashToString(4, MD5.toCharArray());
            assertTrue(stored, PasswordUtil.passwordMatch(MD5, stored));
            assertFalse(stored, PasswordUtil.passwordMatch(OTHER_MD5, stored));
        }
    }

    @Test
    public void malformedStoredValuesMatchNothingAndNeverThrow() {
        String good = PasswordUtil.getHashFromMd5(MD5);
        String[] malformed = {null, "", " ", MD5, "$", "$2", "$2a$12$", "$2a$12$short", good.substring(0, 59),
                good + "x", "$2a$xx$" + good.substring(7), "$2z$12$" + good.substring(7),
                legacy(MD5).substring(1), legacy(MD5) + "0", repeat('*', 40), "$1$abcdefgh$abcdefghijklmnopqrstuv"};
        String huge = repeat('x', 5000);
        for (String stored : malformed) {
            assertFalse("matched " + stored, PasswordUtil.passwordMatch(MD5, stored));
            assertFalse("matched " + stored, PasswordUtil.passwordMatch(null, stored));
            assertFalse("matched " + stored, PasswordUtil.passwordMatch(huge, stored));
        }
        assertFalse("an over-long input is refused", PasswordUtil.passwordMatch(huge, legacy(huge)));
    }

    /**
     * Every check runs one bcrypt, whatever is stored and entered: a legacy, malformed or missing hash (an unknown
     * account) and a non-MD5 input take as long as a real bcrypt check, so the time tells nothing about the account.
     * Without that bcrypt, those checks would take microseconds against a bcrypt's tens of milliseconds, so the
     * bound (a fifth of a bcrypt check) is far from both and does not depend on the machine's speed.
     */
    @Test
    public void everyCheckCostsOneBcrypt() throws Exception {
        String bcrypt = PasswordUtil.getHashFromMd5(MD5);
        String legacy = legacy(MD5);
        // The fastest of the runs: a loaded machine only slows the others down, so the bound can't fail from load.
        long bcryptCheck = minNanos(() -> PasswordUtil.passwordMatch(OTHER_MD5, bcrypt));
        long[] others = {
                medianNanos(() -> PasswordUtil.passwordMatch(OTHER_MD5, legacy)),
                medianNanos(() -> PasswordUtil.passwordMatch(MD5, legacy)),
                medianNanos(() -> PasswordUtil.passwordMatch(OTHER_MD5, null)),
                medianNanos(() -> PasswordUtil.passwordMatch(OTHER_MD5, "$2a$12$short")),
                medianNanos(() -> PasswordUtil.passwordMatch("not an md5", bcrypt)),
        };
        for (long t : others) {
            assertTrue("bcrypt check " + bcryptCheck + "ns, this one " + t + "ns", t * 5 > bcryptCheck);
        }
        Field dummy = PasswordUtil.class.getDeclaredField("dummyHash");
        dummy.setAccessible(true);
        String d = (String) dummy.get(null);
        assertNotNull(d);
        assertTrue("the stand-in costs what a stored hash does", d.startsWith("$2a$" + PasswordUtil.BCRYPT_COST + "$"));
    }

    /**
     * Comparisons do not stop at the first differing character: a stored hash differing from the right one only in
     * its last character is refused like any other (bcrypt compares with Bytes.equalsConstantTime, the legacy format
     * with MessageDigest.isEqual).
     */
    @Test
    public void aHashOffByItsLastCharacterIsRefused() {
        String legacy = legacy(MD5);
        char l = legacy.charAt(39);
        assertFalse(PasswordUtil.passwordMatch(MD5, legacy.substring(0, 39) + (l == 'A' ? 'B' : 'A')));
        String bcrypt = BCrypt.withDefaults().hashToString(4, MD5.toCharArray());
        char b = bcrypt.charAt(58);
        assertFalse(PasswordUtil.passwordMatch(MD5, bcrypt.substring(0, 58) + (b == 'O' ? 'P' : 'O') + bcrypt.charAt(59)));
    }

    private static long minNanos(Runnable r) {
        long min = Long.MAX_VALUE;
        r.run();
        for (int i = 0; i < 5; i++) {
            long s = System.nanoTime();
            r.run();
            min = Math.min(min, System.nanoTime() - s);
        }
        return min;
    }

    private static long medianNanos(Runnable r) {
        long[] t = new long[5];
        r.run();
        for (int i = 0; i < t.length; i++) {
            long s = System.nanoTime();
            r.run();
            t[i] = System.nanoTime() - s;
        }
        Arrays.sort(t);
        return t[t.length / 2];
    }
}
