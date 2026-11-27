// Derived from Headwind MDM (Apache-2.0); modified by the MDMesh contributors.
package com.hmdm.util;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Random;

public class PasswordUtil {
    public static final int PASS_STRENGTH_NONE = 0;
    public static final int PASS_STRENGTH_ALPHADIGIT = 1;
    public static final int PASS_STRENGTH_SPECIAL = 2;

    // SecureRandom: this generator feeds passwords, authTokens (session/JWT binding) and password-reset tokens.
    private static final Random random = new SecureRandom();

    private static final String PASS_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_-.,!#$%()=+;*/";
    private static int DIGIT_START = 0;
    private static int DIGIT_END = 9;
    private static int ALPHA_LOWER_START = 10;
    private static int ALPHA_LOWER_END = 35;
    private static int ALPHA_CAPS_START = 36;
    private static int ALPHA_CAPS_END = 61;
    private static int ALPHA_CHAR_START = 62;
    private static int ALPHA_CHAR_END = 76;

    /*
     * Stored password formats (users.password). The clients send MD5(password) in hex at sign-in, never the password,
     * so what is stored is a hash of that MD5, and the sign-in protocol stays as it was.
     *
     *   bcrypt   "$2a$" + cost + salt and hash, 60 characters: bcrypt(UPPER(md5)), random salt, cost BCRYPT_COST. What
     *            every password set or upgraded now is stored as.
     *   legacy   SHA-1(md5 + PASS_SALT) in hex, 40 characters, one salt for every account: what releases up to 0.4.x
     *            wrote (and the installers' seed still writes, install/lib/db.sh). Still accepted; replaced with
     *            bcrypt at its owner's next sign-in (UnsecureDAO.checkPasswordUnsecure).
     *
     * A legacy value is hex, so it never starts with "$": the prefix alone tells the formats apart.
     */

    private static final String PASS_SALT = "5YdSYHyg2U";

    /** bcrypt work factor of every hash written now. A stored hash with a lower cost is replaced at sign-in. */
    public static final int BCRYPT_COST = 11;

    /** The modular crypt prefix of bcrypt ($2a$, $2b$, $2y$). */
    private static final String BCRYPT_PREFIX = "$2";

    private static final int LEGACY_LENGTH = 40;

    /** The longest input compared with a legacy hash, in characters. */
    private static final int MAX_LEGACY_INPUT = 1024;

    /** What a non-MD5 input is checked as, so a malformed input costs the same single bcrypt as a well-formed one. */
    private static final String FILLER_MD5 = "00000000000000000000000000000000";

    /** A bcrypt hash of a random value, compared with when there is no bcrypt hash to check (built on first use). */
    private static volatile String dummyHash;

    /** The value stored for a password typed in plain: what a client would send for it (its MD5), hashed. */
    public static String getHashFromRaw(String password) {
        return getHashFromMd5(CryptoUtil.getMD5String(password));
    }

    /**
     * Whether {@code value} is what the clients send for a password: an MD5 in hex, 32 characters, either case.
     * Only such a value can be stored as a password or match a bcrypt hash.
     */
    public static boolean isMd5Hex(String value) {
        if (value == null || value.length() != 32) {
            return false;
        }
        for (int i = 0; i < 32; i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The value stored for the MD5 (hex) a client sends: bcrypt of it upper-cased, cost {@link #BCRYPT_COST}, random
     * salt, "$2a$11$..." (60 characters).
     *
     * @throws IllegalArgumentException if {@code md5} is not 32 hex characters ({@link #isMd5Hex}). Callers taking
     *                                  a new password from a request check that first and answer a validation error.
     */
    public static String getHashFromMd5(String md5) {
        if (!isMd5Hex(md5)) {
            throw new IllegalArgumentException("A password is stored from its MD5 in hex (32 characters)");
        }
        return BCrypt.withDefaults().hashToString(BCRYPT_COST, upper(md5));
    }

    /**
     * Whether {@code enteredPass} (what a client sent: normally an MD5 in hex) is the password stored as
     * {@code dbPass}, in either format. Never throws. Every call costs one bcrypt verification, whatever is stored
     * (bcrypt, legacy, malformed or null) and whatever was entered, and both formats are compared in constant time,
     * so the time taken tells nothing about the account or how far a guess matched.
     * <p>A bcrypt hash matches only an MD5 (either case). A legacy hash is compared as 0.4.x did, with the input
     * exactly as sent (up to {@value #MAX_LEGACY_INPUT} characters): its API took any string as a password.</p>
     */
    public static boolean passwordMatch(String enteredPass, String dbPass) {
        boolean md5 = isMd5Hex(enteredPass);
        char[] candidate = md5 ? upper(enteredPass) : FILLER_MD5.toCharArray();
        if (isBcrypt(dbPass)) {
            BCrypt.Result result;
            try {
                result = BCrypt.verifyer().verify(candidate, dbPass);
            } catch (RuntimeException e) {
                result = null;
            }
            if (result == null || !result.validFormat) {
                burnOneCheck(candidate);    // malformed: no bcrypt ran, so run one
                return false;
            }
            return md5 && result.verified;
        }
        burnOneCheck(candidate);
        return legacyMatch(enteredPass, dbPass);
    }

    /**
     * Whether a stored hash that just matched should be replaced with {@link #getHashFromMd5} of the input: a legacy
     * hash, or a bcrypt hash below {@link #BCRYPT_COST}. Only an MD5 input can be stored; the caller checks that.
     */
    public static boolean needsRehash(String dbPass) {
        if (!isBcrypt(dbPass)) {
            return true;
        }
        try {
            return Integer.parseInt(dbPass.substring(4, 6)) < BCRYPT_COST;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Whether {@code dbPass} is in the bcrypt format (by its prefix; it may still be malformed). */
    public static boolean isBcrypt(String dbPass) {
        return dbPass != null && dbPass.startsWith(BCRYPT_PREFIX);
    }

    private static boolean legacyMatch(String enteredPass, String dbPass) {
        if (enteredPass == null || enteredPass.length() > MAX_LEGACY_INPUT
                || dbPass == null || dbPass.length() != LEGACY_LENGTH) {
            return false;
        }
        byte[] expected = dbPass.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = CryptoUtil.getSHA1String(enteredPass + PASS_SALT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private static char[] upper(String md5) {
        return md5.toUpperCase(Locale.ROOT).toCharArray();
    }

    /** One bcrypt verification whose result is ignored: a check with no well-formed bcrypt hash to compare. */
    private static void burnOneCheck(char[] candidate) {
        String h = dummyHash;
        if (h == null) {
            h = BCrypt.withDefaults().hashToString(BCRYPT_COST, generateToken().toCharArray());
            dummyHash = h;
        }
        BCrypt.verifyer().verify(candidate, h);
    }

    public static boolean checkPassword(String password, int length, int strength) {
        if (password.length() < length) {
            return false;
        }

        if (strength == PASS_STRENGTH_NONE) {
            return true;
        }

        boolean hasDigits = false;
        boolean hasLower = false;
        boolean hasCaps = false;
        boolean hasSpecial = false;

        for (int n = 0; n < password.length(); n++) {
            int i = PASS_CHARS.indexOf(password.charAt(n));
            if (i == -1) {
                hasSpecial = true;
            } else if (i >= DIGIT_START && i <= DIGIT_END) {
                hasDigits = true;
            } else if (i >= ALPHA_LOWER_START && i <= ALPHA_LOWER_END) {
                hasLower = true;
            } else if (i >= ALPHA_CAPS_START && i <= ALPHA_CAPS_END) {
                hasCaps = true;
            } else if (i >= ALPHA_CHAR_START && i <= ALPHA_CHAR_END) {
                hasSpecial = true;
            }
        }
        if (strength == PASS_STRENGTH_ALPHADIGIT) {
            return hasDigits && hasLower && hasCaps;
        }
        if (strength == PASS_STRENGTH_SPECIAL) {
            return hasDigits && hasLower && hasCaps && hasSpecial;
        }
        // Reserved
        return false;
    }

    public static String generatePassword(int length, int strength) {
        int realLength = length < 8 ? 8 : length;

        int charIntervalEnd = DIGIT_END;
        switch (strength) {
            case PASS_STRENGTH_NONE:
                charIntervalEnd = DIGIT_END;
                break;
            case PASS_STRENGTH_ALPHADIGIT:
                charIntervalEnd = ALPHA_CAPS_END;
                break;
            case PASS_STRENGTH_SPECIAL:
                charIntervalEnd = ALPHA_CHAR_END;
                break;
        }

        StringBuilder b = new StringBuilder();
        for (int n = 0; n < realLength - 3; n++) {
            int index = random.nextInt(charIntervalEnd + 1);
            b.append(PASS_CHARS.charAt(index));
        }

        String password = b.toString();
        if (!checkPassword(password, length, strength)) {
            // Let's update password to match rules
            int index = random.nextInt(DIGIT_END - DIGIT_START + 1);
            b.append(PASS_CHARS.charAt(index));
            index = random.nextInt(ALPHA_LOWER_END - ALPHA_LOWER_START + 1);
            b.append(PASS_CHARS.charAt(ALPHA_LOWER_START + index));
            index = random.nextInt(ALPHA_CAPS_END - ALPHA_CAPS_START + 1);
            b.append(PASS_CHARS.charAt(ALPHA_CAPS_START + index));
            if (strength == PASS_STRENGTH_SPECIAL) {
                index = random.nextInt(ALPHA_CHAR_END - ALPHA_CHAR_START + 1);
                b.append(PASS_CHARS.charAt(ALPHA_CHAR_START + index));
            }
        } else {
            for (int n = 0; n < 3; n++) {
                int index = random.nextInt(charIntervalEnd + 1);
                b.append(PASS_CHARS.charAt(index));
            }
        }
        password = b.toString();
        return password;
    }

    public static String generateToken() {
        StringBuilder b = new StringBuilder();
        for (int n = 0; n < 20; n++) {
            b.append(PASS_CHARS.charAt(random.nextInt(ALPHA_CAPS_END)));
        }
        return b.toString();
    }

}
