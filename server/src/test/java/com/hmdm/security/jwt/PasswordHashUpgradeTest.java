package com.hmdm.security.jwt;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.hmdm.auth.LocalAuth;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Settings;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.persistence.mapper.CommonMapper;
import com.hmdm.persistence.mapper.CustomerMapper;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.rest.json.UserCredentials;
import com.hmdm.rest.resource.AuthResource;
import com.hmdm.security.LoginThrottle;
import com.hmdm.security.jwt.rest.JWTAuthResource;
import com.hmdm.service.EmailService;
import com.hmdm.testsupport.FakeSessions;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.BackgroundTaskRunnerService;
import com.hmdm.util.CryptoUtil;
import com.hmdm.util.PasswordUtil;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A legacy password hash (SHA-1 with one global salt, 0.4.x) is replaced with bcrypt at its owner's next sign-in,
 * through the console (AuthResource + LocalAuth) and the API (JWTAuthResource), with the users row behind a fake
 * mapper. Only the hash changes, and only while the row still holds the hash that was checked.
 */
public class PasswordHashUpgradeTest {

    private static final String MD5 = "5F4DCC3B5AA765D61D8327DEB882CF99";
    private static final String WRONG_MD5 = "0123456789ABCDEF0123456789ABCDEF";
    private static final String LEGACY = CryptoUtil.getSHA1String(MD5 + "5YdSYHyg2U");

    /** The users row: what a lookup reads (a fresh copy each time, like a query) and upgradePasswordHash writes. */
    private String row;
    private Fakes.FakeMapper<UserMapper> users;
    private UnsecureDAO dao;
    private AuthResource console;
    private JWTAuthResource api;

    @Before
    public void setUp() {
        users = new Fakes.FakeMapper<>(UserMapper.class);
        users.answer("upgradePasswordHash", a -> {
            if (!Objects.equals(row, a[1])) {
                return 0;
            }
            row = (String) a[2];
            return 1;
        });
        dao = new UnsecureDAO(null, users.mapper, null, new Fakes.FakeMapper<>(CommonMapper.class).mapper, null, null,
                null, null, null, null, null, null, new Fakes.FakeMapper<>(CustomerMapper.class).mapper, null, ".", 2,
                "com.hmdm.launcher") {
            @Override
            public User findByLoginOrEmail(String login) {
                return "admin".equals(login) ? account() : null;
            }

            @Override
            public Settings getSettings(int customerId) {
                return new Settings();
            }
        };
        BackgroundTaskRunnerService tasks = new BackgroundTaskRunnerService() {
            @Override
            public void submitTask(Runnable task) {
            }
        };
        // One address, and far fewer failures than LoginThrottle refuses
        LoginThrottle throttle = new LoginThrottle("", false, JWTSignInTest.SECRET, "http://localhost");
        console = new AuthResource(dao, null, dao, tasks,
                new EmailService("", 25, false, false, "", "", "", "", "mdm@localhost", "", "", "", ""),
                null, false, new LocalAuth(dao), throttle);
        api = new JWTAuthResource(new TokenProvider(JWTSignInTest.SECRET + JWTSignInTest.SECRET, "3600", "3600"),
                dao, null, tasks, throttle);
    }

    @Test
    public void aConsoleSignInStoresALegacyHashAsBcrypt() {
        row = LEGACY;

        assertTrue(consoleSignIn(MD5));

        assertTrue(row, row.startsWith("$2a$" + PasswordUtil.BCRYPT_COST + "$"));
        assertTrue(PasswordUtil.passwordMatch(MD5, row));
        List<Fakes.Call> upgrades = users.callsTo("upgradePasswordHash");
        assertEquals(1, upgrades.size());
        assertEquals(1, upgrades.get(0).args[0]);
        assertEquals("only while the row still holds the hash checked", LEGACY, upgrades.get(0).args[1]);
        assertNoOtherPasswordWrite();

        // The next sign-in checks the bcrypt hash and writes nothing
        assertTrue(consoleSignIn(MD5));
        assertFalse(consoleSignIn(WRONG_MD5));
        assertEquals(1, users.callsTo("upgradePasswordHash").size());
    }

    @Test
    public void anApiSignInStoresALegacyHashAsBcrypt() {
        row = LEGACY;

        assertEquals(200, apiSignIn(MD5));

        assertTrue(PasswordUtil.isBcrypt(row));
        assertTrue(PasswordUtil.passwordMatch(MD5, row));
        assertEquals(1, users.callsTo("upgradePasswordHash").size());
        assertNoOtherPasswordWrite();
        assertEquals(200, apiSignIn(MD5));
        assertEquals(1, users.callsTo("upgradePasswordHash").size());
    }

    @Test
    public void theMd5IsAcceptedInEitherCaseAfterTheUpgrade() {
        row = LEGACY;
        assertTrue(consoleSignIn(MD5));

        assertEquals(200, apiSignIn(MD5.toLowerCase()));
    }

    @Test
    public void aWrongPasswordWritesNothing() {
        row = LEGACY;

        assertFalse(consoleSignIn(WRONG_MD5));
        assertEquals(401, apiSignIn(WRONG_MD5));
        assertFalse(consoleSignIn(LEGACY));

        assertEquals(LEGACY, row);
        assertTrue(users.calls.isEmpty());
    }

    @Test
    public void aCurrentBcryptHashIsLeftAsItIs() {
        row = PasswordUtil.getHashFromMd5(MD5);
        String before = row;

        assertTrue(consoleSignIn(MD5));
        assertEquals(200, apiSignIn(MD5));

        assertEquals(before, row);
        assertTrue(users.callsTo("upgradePasswordHash").isEmpty());
    }

    @Test
    public void aBcryptHashBelowTheCurrentCostIsRaised() {
        row = BCrypt.withDefaults().hashToString(4, MD5.toCharArray());

        assertTrue(consoleSignIn(MD5));

        assertTrue(row, row.startsWith("$2a$" + PasswordUtil.BCRYPT_COST + "$"));
        assertTrue(PasswordUtil.passwordMatch(MD5, row));
    }

    /** 0.4.x's API took any string as a password. Such an account still signs in; bcrypt is only stored for an MD5. */
    @Test
    public void aLegacyPasswordThatIsNoMd5SignsInAndStaysLegacy() {
        row = CryptoUtil.getSHA1String("plain text" + "5YdSYHyg2U");
        String before = row;

        assertEquals(200, apiSignIn("plain text"));

        assertEquals(before, row);
        assertTrue(users.callsTo("upgradePasswordHash").isEmpty());
    }

    @Test
    public void aPasswordChangedMeanwhileIsNeverOverwritten() {
        row = LEGACY;
        String changed = PasswordUtil.getHashFromMd5(WRONG_MD5);
        users.answer("upgradePasswordHash", a -> {
            row = changed;               // another request changed the password between the check and the write
            return Objects.equals(row, a[1]) ? 1 : 0;
        });

        assertTrue("the sign-in itself succeeds", consoleSignIn(MD5));

        assertEquals(changed, row);
    }

    @Test
    public void aFailedWriteStillSignsIn() {
        row = LEGACY;
        users.answer("upgradePasswordHash", a -> {
            throw new IllegalStateException("database unavailable");
        });

        assertTrue(consoleSignIn(MD5));
        assertEquals(200, apiSignIn(MD5));

        assertEquals(LEGACY, row);
    }

    @Test
    public void theDaoCheckRefusesAMissingAccount() {
        assertFalse(dao.checkPasswordUnsecure(null, MD5));
        assertTrue(users.calls.isEmpty());
    }

    // -----------------------------------------------------------------------------------------------------------------

    private User account() {
        UserRole role = new UserRole();
        role.setId(2);
        User u = new User();
        u.setId(1);
        u.setLogin("admin");
        u.setCustomerId(1);
        u.setUserRole(role);
        u.setPassword(row);
        u.setAuthToken("token");
        return u;
    }

    private void assertNoOtherPasswordWrite() {
        assertTrue("sessions and reset state untouched: " + users.calls,
                users.callsStartingWith("setNewPassword", "updatePassword", "setAuthToken").isEmpty());
    }

    private boolean consoleSignIn(String md5) {
        UserCredentials c = new UserCredentials();
        c.setLogin("admin");
        c.setPassword(md5);
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (p, m, a) -> null);
        return console.login(c, request(new FakeSessions()), response).getStatus()
                == com.hmdm.rest.json.Response.ResponseStatus.OK;
    }

    private int apiSignIn(String md5) {
        UserCredentials c = new UserCredentials();
        c.setLogin("admin");
        c.setPassword(md5);
        return api.login(c, request(new FakeSessions())).getStatus();
    }

    private static HttpServletRequest request(FakeSessions sessions) {
        return (HttpServletRequest) Proxy.newProxyInstance(PasswordHashUpgradeTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getSession": return sessions.getSession(a);
                        case "getRemoteAddr": return "192.0.2.10";
                        case "getHeaders": return Collections.emptyEnumeration();
                        default: return null;
                    }
                });
    }
}
