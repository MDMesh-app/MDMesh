/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.util;

import com.hmdm.persistence.domain.User;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PasswordResetTokensTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 60L * 60 * 1000;

    @Test
    public void aTokenIsGoodForADay() {
        User u = new User();
        PasswordResetTokens.issue(u, NOW);

        assertNotNull(u.getPasswordResetToken());
        assertEquals(Long.valueOf(NOW), u.getPasswordResetTokenTime());
        assertTrue(PasswordResetTokens.isFresh(u, NOW));
        assertTrue(PasswordResetTokens.isFresh(u, NOW + 23 * HOUR));
        assertFalse(PasswordResetTokens.isFresh(u, NOW + 24 * HOUR));
        assertFalse(PasswordResetTokens.isFresh(u, NOW + 30 * 24 * HOUR));
    }

    @Test
    public void aTokenWithoutIssueTimeHasExpired() {
        User u = new User();
        u.setPasswordResetToken("seeded-by-an-installer");

        assertFalse(PasswordResetTokens.isFresh(u, NOW));
    }

    @Test
    public void noTokenIsNotFresh() {
        User u = new User();
        u.setPasswordResetTokenTime(NOW);

        assertFalse(PasswordResetTokens.isFresh(u, NOW));
        u.setPasswordResetToken("");
        assertFalse(PasswordResetTokens.isFresh(u, NOW));
    }

    @Test
    public void aTokenFromTheFarFutureIsNotFresh() {
        User u = new User();
        PasswordResetTokens.issue(u, NOW + 48 * HOUR);

        assertFalse(PasswordResetTokens.isFresh(u, NOW));
    }

    @Test
    public void eachIssueIsANewTokenAndClearRemovesIt() {
        User u = new User();
        PasswordResetTokens.issue(u, NOW);
        String first = u.getPasswordResetToken();
        PasswordResetTokens.issue(u, NOW);
        assertNotEquals(first, u.getPasswordResetToken());

        PasswordResetTokens.clear(u);
        assertNull(u.getPasswordResetToken());
        assertNull(u.getPasswordResetTokenTime());
        assertFalse(PasswordResetTokens.isFresh(u, NOW));
    }

    @Test
    public void theIssueTimeIsNeverInJson() throws Exception {
        User u = new User();
        u.setUserRole(new com.hmdm.persistence.domain.UserRole());
        PasswordResetTokens.issue(u, NOW);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(u);
        assertFalse(json, json.contains("passwordResetTokenTime"));

        User read = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"passwordResetTokenTime\":" + (NOW + 99 * HOUR) + "}", User.class);
        assertNull("a request cannot set the issue time", read.getPasswordResetTokenTime());
    }
}
