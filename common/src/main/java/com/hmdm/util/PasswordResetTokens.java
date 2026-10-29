/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.util;

import com.hmdm.persistence.domain.User;

/**
 * Password reset tokens: the recovery mail's link, and the forced change at first sign-in. A token is good for
 * {@link #TTL_MS} after it is issued, and for one password change (setting a password clears it). A token without an
 * issue time (one written before the time was recorded, or by an installer's seed) counts as expired; signing in with
 * the password issues a fresh one where the change is forced.
 */
public final class PasswordResetTokens {

    public static final long TTL_MS = 24L * 60 * 60 * 1000;

    private PasswordResetTokens() {
    }

    /** Gives the user a new token issued at {@code now}. The caller stores it. */
    public static void issue(User user, long now) {
        user.setPasswordResetToken(PasswordUtil.generateToken());
        user.setPasswordResetTokenTime(now);
    }

    /** Removes the user's token. The caller stores it. */
    public static void clear(User user) {
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenTime(null);
    }

    /** True when the user holds a token issued less than {@link #TTL_MS} before {@code now}. */
    public static boolean isFresh(User user, long now) {
        String token = user.getPasswordResetToken();
        Long issued = user.getPasswordResetTokenTime();
        // A minute of slack for a clock that stepped back since the token was issued.
        return token != null && !token.isEmpty() && issued != null && now - issued < TTL_MS && issued - now < 60_000;
    }
}
