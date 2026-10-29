/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.persistence.mapper.CustomerMapper;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.service.EmailService;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.PasswordResetTokens;
import com.hmdm.util.PasswordUtil;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * POST /public/passwordReset/reset and GET /public/passwordReset/recover/{username}: a reset link is good for a day
 * and for one change, setting the password ends the user's sessions, and asking for a link ends none. The reset is
 * public (whoever holds a token calls it), so its answer says only that the password was set and never echoes the
 * account: the stored password hash travelled in {@code newPassword}.
 */
public class PasswordResetResourceTest {

    private static final long HOUR = 60L * 60 * 1000;
    private static final String NEW_MD5 = "0123456789ABCDEF0123456789ABCDEF";

    private User stored;
    private Fakes.FakeMapper<UserMapper> users;
    private final List<String> mailed = new ArrayList<>();
    private PasswordResetResource resource;

    @Before
    public void setUp() {
        stored = new User();
        stored.setId(5);
        stored.setLogin("operator");
        stored.setEmail("operator@example.com");
        stored.setCustomerId(1);
        stored.setUserRole(new UserRole());
        stored.setAuthToken("session-token");
        stored.setPassword("old-hash");

        users = new Fakes.FakeMapper<>(UserMapper.class);
        users.answer("findByPasswordResetToken",
                a -> a[0] != null && a[0].equals(stored.getPasswordResetToken()) ? copy(stored) : null);
        users.answer("findByLogin", a -> "operator".equals(a[0]) ? copy(stored) : null);
        users.answer("setNewPassword", a -> {
            User u = (User) a[0];
            stored.setPassword(u.getNewPassword());
            stored.setAuthToken(u.getAuthToken());
            stored.setPasswordReset(u.isPasswordReset());
            stored.setPasswordResetToken(u.getPasswordResetToken());
            stored.setPasswordResetTokenTime(u.getPasswordResetTokenTime());
            return null;
        });
        users.answer("setPasswordResetToken", a -> {
            User u = (User) a[0];
            stored.setPasswordResetToken(u.getPasswordResetToken());
            stored.setPasswordResetTokenTime(u.getPasswordResetTokenTime());
            return null;
        });
        Fakes.FakeMapper<CustomerMapper> customers = new Fakes.FakeMapper<>(CustomerMapper.class);
        UnsecureDAO unsecureDAO = new UnsecureDAO(null, users.mapper, null, null, null, null, null, null, null, null,
                null, null, customers.mapper, null, ".", 2, "com.hmdm.launcher");
        EmailService email = new EmailService("smtp.example.com", 25, false, false, "", "", "", "", "mdm@localhost",
                "", "https://mdm.example.com", "", "") {
            @Override
            public boolean sendEmail(String to, String subj, String body) {
                mailed.add(to);
                return true;
            }

            @Override
            public String getRecoveryEmailSubj(String language) {
                return "subject";
            }

            @Override
            public String getRecoveryEmailBody(String language, String passwordResetToken) {
                return "token " + passwordResetToken;
            }
        };
        resource = new PasswordResetResource(null, unsecureDAO, email, "https://mdm.example.com");
    }

    // ---- reset -------------------------------------------------------------------------------------------------------

    @Test
    public void aResetEndsEverySessionOfTheUser() {
        issue(System.currentTimeMillis());

        Response response = resource.updatePassword(reset(stored.getPasswordResetToken()));

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(1, users.callsTo("setNewPassword").size());
        assertNotNull(stored.getAuthToken());
        assertNotEquals("the sessions and JWTs of the old password end", "session-token", stored.getAuthToken());
        assertNull(stored.getPasswordResetToken());
        assertNull(stored.getPasswordResetTokenTime());
    }

    @Test
    public void aResetAnswersOkWithNoUserData() throws Exception {
        issue(System.currentTimeMillis());

        Response response = resource.updatePassword(reset(stored.getPasswordResetToken()));

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertNull("no account in the answer", response.getData());
        String json = new ObjectMapper().writeValueAsString(response);
        for (String leak : new String[]{"newPassword", "operator@example.com", "\"login\"", "old-hash",
                "session-token", stored.getPassword(), stored.getAuthToken()}) {
            assertFalse(leak + " in " + json, json.contains(leak));
        }
    }

    @Test
    public void aResetStoresTheNewPassword() {
        issue(System.currentTimeMillis());

        resource.updatePassword(reset(stored.getPasswordResetToken()));

        assertTrue(PasswordUtil.passwordMatch(NEW_MD5, stored.getPassword()));
        assertFalse(stored.isPasswordReset());
    }

    @Test
    public void anUnknownTokenIsRefusedAndWritesNothing() {
        issue(System.currentTimeMillis());

        Response response = resource.updatePassword(reset("other"));

        assertEquals(Response.ResponseStatus.ERROR, response.getStatus());
        assertEquals("error.user.not.found", response.getMessage());
        assertTrue(users.callsTo("setNewPassword").isEmpty());
    }

    @Test
    public void aResetLinkWorksOnce() {
        issue(System.currentTimeMillis());
        String token = stored.getPasswordResetToken();

        assertEquals(Response.ResponseStatus.OK, resource.updatePassword(reset(token)).getStatus());
        Response again = resource.updatePassword(reset(token));

        assertEquals(Response.ResponseStatus.ERROR, again.getStatus());
        assertEquals(1, users.callsTo("setNewPassword").size());
    }

    @Test
    public void aResetLinkExpiresAfterADay() {
        issue(System.currentTimeMillis() - 25 * HOUR);

        Response response = resource.updatePassword(reset(stored.getPasswordResetToken()));

        assertEquals(Response.ResponseStatus.ERROR, response.getStatus());
        assertTrue(users.callsTo("setNewPassword").isEmpty());
        assertEquals("session-token", stored.getAuthToken());
    }

    @Test
    public void aTokenWithoutIssueTimeIsRefused() {
        stored.setPasswordResetToken("written-before-the-upgrade");

        Response response = resource.updatePassword(reset("written-before-the-upgrade"));

        assertEquals(Response.ResponseStatus.ERROR, response.getStatus());
        assertTrue(users.callsTo("setNewPassword").isEmpty());
    }

    @Test
    public void anEmptyTokenFindsNobody() {
        stored.setPasswordResetToken("");
        stored.setPasswordResetTokenTime(System.currentTimeMillis());

        assertEquals(Response.ResponseStatus.ERROR, resource.updatePassword(reset("")).getStatus());
        assertEquals(Response.ResponseStatus.ERROR, resource.updatePassword(reset(null)).getStatus());
        assertTrue(users.callsTo("setNewPassword").isEmpty());
    }

    @Test
    public void theSettingsOfAnExpiredLinkAreRefused() {
        issue(System.currentTimeMillis() - 25 * HOUR);

        assertEquals(Response.ResponseStatus.ERROR, resource.getSettings(stored.getPasswordResetToken()).getStatus());
    }

    // ---- recover -----------------------------------------------------------------------------------------------------

    @Test
    public void aRecoveryRequestIssuesADatedLinkAndEndsNoSession() {
        long before = System.currentTimeMillis();

        Response response = resource.recover("operator");

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(1, users.callsTo("setPasswordResetToken").size());
        assertNotNull(stored.getPasswordResetToken());
        assertTrue(stored.getPasswordResetTokenTime() >= before);
        assertTrue("asking for a link changes neither the password nor the sessions",
                users.callsStartingWith("setNewPassword", "updatePassword", "setAuthToken").isEmpty());
        assertEquals("session-token", stored.getAuthToken());
        assertEquals(1, mailed.size());
    }

    @Test
    public void aRecoveryRequestReplacesAnExpiredLink() {
        issue(System.currentTimeMillis() - 25 * HOUR);
        String expired = stored.getPasswordResetToken();

        resource.recover("operator");

        assertNotEquals(expired, stored.getPasswordResetToken());
        assertTrue(PasswordResetTokens.isFresh(stored, System.currentTimeMillis()));
    }

    @Test
    public void aRecoveryRequestResendsALinkStillGood() {
        issue(System.currentTimeMillis() - HOUR);
        String sent = stored.getPasswordResetToken();

        resource.recover("operator");

        assertEquals(sent, stored.getPasswordResetToken());
        assertTrue(users.callsTo("setPasswordResetToken").isEmpty());
    }

    @Test
    public void recoveryMailsAreLimitedPerAccount() {
        for (int i = 0; i < PasswordResetResource.RECOVERY_MAILS_PER_HOUR + 2; i++) {
            assertEquals(Response.ResponseStatus.OK, resource.recover("operator").getStatus());
        }

        assertEquals("a few mails an hour, not one per request", PasswordResetResource.RECOVERY_MAILS_PER_HOUR,
                mailed.size());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private void issue(long when) {
        PasswordResetTokens.issue(stored, when);
    }

    private static User reset(String token) {
        User body = new User();
        body.setPasswordResetToken(token);
        body.setNewPassword(NEW_MD5);
        return body;
    }

    private static User copy(User source) {
        User u = new User();
        u.setId(source.getId());
        u.setLogin(source.getLogin());
        u.setEmail(source.getEmail());
        u.setCustomerId(source.getCustomerId());
        u.setUserRole(source.getUserRole());
        u.setAuthToken(source.getAuthToken());
        u.setPassword(source.getPassword());
        u.setPasswordReset(source.isPasswordReset());
        u.setPasswordResetToken(source.getPasswordResetToken());
        u.setPasswordResetTokenTime(source.getPasswordResetTokenTime());
        return u;
    }
}
