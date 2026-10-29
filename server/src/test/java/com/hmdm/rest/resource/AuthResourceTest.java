/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.auth.HmdmAuthInterface;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.persistence.mapper.CommonMapper;
import com.hmdm.persistence.mapper.CustomerMapper;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.rest.json.AuthOptionsResponse;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.UserCredentials;
import com.hmdm.rest.json.view.user.UserView;
import com.hmdm.security.LoginThrottle;
import com.hmdm.service.EmailService;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.BackgroundTaskRunnerService;
import com.hmdm.util.PasswordResetTokens;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** POST /public/auth/login and GET /public/auth/options. */
public class AuthResourceTest {

    private User stored;
    private Fakes.FakeMapper<UserMapper> users;
    private Fakes.FakeMapper<CommonMapper> settings;
    private AuthResource resource;

    @Before
    public void setUp() {
        stored = new User();
        stored.setId(1);
        stored.setLogin("admin");
        stored.setCustomerId(1);
        stored.setUserRole(new UserRole());
        stored.setAuthToken("session-token");

        users = new Fakes.FakeMapper<>(UserMapper.class);
        settings = new Fakes.FakeMapper<>(CommonMapper.class);
        Fakes.FakeMapper<CustomerMapper> customers = new Fakes.FakeMapper<>(CustomerMapper.class);
        UnsecureDAO unsecureDAO = new UnsecureDAO(null, users.mapper, null, settings.mapper, null, null, null, null,
                null, null, null, null, customers.mapper, null, ".", 2, "com.hmdm.launcher");
        HmdmAuthInterface auth = new HmdmAuthInterface() {
            @Override
            public User findUser(String login) {
                return "admin".equals(login) ? stored : null;
            }

            @Override
            public boolean authenticate(User user, String password) {
                return "right".equals(password);
            }
        };
        BackgroundTaskRunnerService tasks = new BackgroundTaskRunnerService() {
            @Override
            public void submitTask(Runnable task) {
            }
        };
        EmailService email = new EmailService("smtp.example.com", 25, false, false, "", "", "", "", "mdm@localhost",
                "", "", "", "");
        resource = new AuthResource(unsecureDAO, null, unsecureDAO, tasks, email, null, false, auth, new LoginThrottle("", true,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "https://mdm.example.com"));
    }

    @Test
    public void signupIsNeverOffered() {
        Response response = resource.options();

        assertFalse(((AuthOptionsResponse) response.getData()).isSignup());
        assertTrue("recovery is still offered with SMTP set", ((AuthOptionsResponse) response.getData()).isRecover());
    }

    @Test
    public void aForcedChangeGetsAFreshLinkAtSignIn() throws Exception {
        stored.setPasswordReset(true);
        stored.setPasswordResetToken("seeded-by-the-installer");   // no issue time: expired

        Response response = login(credentials("right"), request());

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(1, users.callsTo("setPasswordResetToken").size());
        String token = ((UserView) response.getData()).getPasswordResetToken();
        assertNotEquals("seeded-by-the-installer", token);
        assertTrue(PasswordResetTokens.isFresh(stored, System.currentTimeMillis()));
        assertTrue("signing in changes neither the password nor the sessions",
                users.callsStartingWith("setNewPassword", "updatePassword").isEmpty());
    }

    @Test
    public void aFreshLinkIsKeptAtSignIn() throws Exception {
        stored.setPasswordReset(true);
        PasswordResetTokens.issue(stored, System.currentTimeMillis());
        String token = stored.getPasswordResetToken();

        Response response = login(credentials("right"), request());

        assertEquals(token, ((UserView) response.getData()).getPasswordResetToken());
        assertTrue(users.callsTo("setPasswordResetToken").isEmpty());
    }

    @Test
    public void aFirstSignInStoresOnlyTheAuthToken() throws Exception {
        stored.setAuthToken(null);
        stored.setPassword("hash");

        Response response = login(credentials("right"), request());

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(1, users.callsTo("setAuthToken").size());
        assertTrue(users.callsStartingWith("setNewPassword", "updatePassword", "setPasswordResetToken").isEmpty());
    }

    @Test
    public void aWrongPasswordIssuesNothing() throws Exception {
        stored.setPasswordReset(true);

        Response response = login(credentials("wrong"), request());

        assertEquals(Response.ResponseStatus.ERROR, response.getStatus());
        assertTrue(users.callsTo("setPasswordResetToken").isEmpty());
    }

    // ---- the limit on failed sign-ins ----------------------------------------------------------------------------------

    @Test
    public void tooManyFailedSignInsAreRefusedWithAKeyTheConsoleShows() throws Exception {
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            Response r = login(credentials("wrong"), request("203.0.113.7"));
            assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
            assertNull("a failure reads as a wrong password", r.getMessage());
        }
        assertTrue("no failed sign-in sleeps", System.nanoTime() - start < 2_000_000_000L);

        Response refused = login(credentials("right"), request("203.0.113.7"));
        assertEquals(Response.ResponseStatus.ERROR, refused.getStatus());
        assertEquals(AuthResource.TOO_MANY_ATTEMPTS, refused.getMessage());

        assertEquals("the owner, elsewhere, still signs in", Response.ResponseStatus.OK,
                login(credentials("right"), request("198.51.100.1")).getStatus());
    }

    @Test
    public void aForwardedForFromAPublicPeerIsNotBelieved() throws Exception {
        // A client on the internet that reaches the server directly can't pick a new address per guess.
        for (int i = 0; i < 10; i++) {
            login(credentials("wrong"), request("203.0.113.7", "X-Forwarded-For", "10.9.9." + i));
        }
        assertEquals(AuthResource.TOO_MANY_ATTEMPTS,
                login(credentials("right"), request("203.0.113.7", "X-Forwarded-For", "10.9.9.99")).getMessage());
    }

    @Test
    public void behindTheEdgeEachClientCountsOnItsOwn() throws Exception {
        // Docker: every request comes from Caddy, which names the client in X-Forwarded-For.
        for (int i = 0; i < 10; i++) {
            login(credentials("wrong"), request("172.18.0.4", "X-Forwarded-For", "203.0.113.7"));
        }
        assertEquals(AuthResource.TOO_MANY_ATTEMPTS,
                login(credentials("right"), request("172.18.0.4", "X-Forwarded-For", "203.0.113.7")).getMessage());
        assertEquals(Response.ResponseStatus.OK,
                login(credentials("right"), request("172.18.0.4", "X-Forwarded-For", "198.51.100.1")).getStatus());
    }

    @Test
    public void aSuccessfulSignInDoesNotCountAgainstTheLimit() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertEquals(Response.ResponseStatus.OK,
                    login(credentials("right"), request("203.0.113.7")).getStatus());
        }
    }

    @Test
    public void aSuccessfulSignInMarksTheBrowserKnownSoAnAttackerCantLockItOut() throws Exception {
        assertEquals(Response.ResponseStatus.OK, login(credentials("right"), request("198.51.100.1")).getStatus());
        assertEquals(1, setCookies.size());
        String cookie = setCookies.get(0);
        assertTrue(cookie, cookie.startsWith(LoginThrottle.KNOWN_BROWSER_COOKIE + "="));
        assertTrue(cookie, cookie.contains("HttpOnly"));
        assertTrue("Secure on an https install", cookie.endsWith("; Secure"));
        String value = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));

        // Failed sign-ins for the account from many addresses: new browsers are refused, the owner's isn't.
        for (int i = 0; i < 40; i++) {
            login(credentials("wrong"), request("203.0.113." + i));
        }
        assertEquals(AuthResource.TOO_MANY_ATTEMPTS,
                login(credentials("right"), request("198.51.100.2")).getMessage());
        assertEquals("the browser, from an address new to the account", Response.ResponseStatus.OK,
                login(credentials("right"), request("198.51.100.3", "Cookie:" + LoginThrottle.KNOWN_BROWSER_COOKIE, value))
                        .getStatus());
        assertEquals("the address it signed in from, without the cookie", Response.ResponseStatus.OK,
                login(credentials("right"), request("198.51.100.1")).getStatus());
    }

    @Test
    public void aSignInThatStillNeedsASecondFactorMakesNeitherTheBrowserNorTheAddressKnown() throws Exception {
        settings.answer("getSettings", a -> {
            com.hmdm.persistence.domain.Settings s = new com.hmdm.persistence.domain.Settings();
            s.setTwoFactor(true);
            return s;
        });
        Response response = login(credentials("right"), request("198.51.100.1"));
        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(Boolean.TRUE, ((UserView) response.getData()).getTwoFactor());
        assertTrue("no known-browser cookie", setCookies.isEmpty());
        for (int i = 0; i < 40; i++) {
            login(credentials("wrong"), request("203.0.113." + i));
        }
        assertEquals("nor a known address", AuthResource.TOO_MANY_ATTEMPTS,
                login(credentials("right"), request("198.51.100.1")).getMessage());
    }

    @Test
    public void aRefusalIsTheSameWhicheverLimitRefused() throws Exception {
        for (int i = 0; i < 10; i++) {
            login(credentials("wrong"), request("203.0.113.7"));   // the address's limit
        }
        for (int i = 0; i < 40; i++) {
            login(credentials("wrong"), request("198.51.100." + i));   // the account's limit
        }
        Response byAddress = login(credentials("right"), request("203.0.113.7"));
        Response byAccount = login(credentials("right"), request("192.0.2.1"));
        assertEquals(AuthResource.TOO_MANY_ATTEMPTS, byAddress.getMessage());
        assertEquals(byAddress.getStatus(), byAccount.getStatus());
        assertEquals(byAddress.getMessage(), byAccount.getMessage());
    }

    private final java.util.List<String> setCookies = new java.util.ArrayList<>();

    private Response login(UserCredentials c, HttpServletRequest r) {
        javax.servlet.http.HttpServletResponse response = (javax.servlet.http.HttpServletResponse) Proxy.newProxyInstance(
                AuthResourceTest.class.getClassLoader(), new Class<?>[]{javax.servlet.http.HttpServletResponse.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addHeader") && "Set-Cookie".equals(args[0])) {
                        setCookies.add((String) args[1]);
                    }
                    return null;
                });
        return resource.login(c, r, response);
    }

    private static UserCredentials credentials(String password) {
        UserCredentials c = new UserCredentials();
        c.setLogin("admin");
        c.setPassword(password);
        return c;
    }

    private static HttpServletRequest request() {
        return request("127.0.0.1");
    }

    private static HttpServletRequest request(String peer, String... headers) {
        HttpSession session = (HttpSession) Proxy.newProxyInstance(AuthResourceTest.class.getClassLoader(),
                new Class<?>[]{HttpSession.class}, (proxy, method, args) -> null);
        java.util.Map<String, String> h = new java.util.HashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            h.put(headers[i], headers[i + 1]);
        }
        return (HttpServletRequest) Proxy.newProxyInstance(AuthResourceTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getCookies": {
                            java.util.List<javax.servlet.http.Cookie> cookies = new java.util.ArrayList<>();
                            h.forEach((k, v) -> {
                                if (k.startsWith("Cookie:")) {
                                    cookies.add(new javax.servlet.http.Cookie(k.substring(7), v));
                                }
                            });
                            return cookies.isEmpty() ? null : cookies.toArray(new javax.servlet.http.Cookie[0]);
                        }
                        case "getSession": return session;
                        case "getRemoteAddr": return peer;
                        case "getHeader": return h.get((String) args[0]);
                        case "getHeaders": return h.containsKey((String) args[0])
                                ? java.util.Collections.enumeration(java.util.Collections.singletonList(h.get((String) args[0])))
                                : java.util.Collections.emptyEnumeration();
                        default: return null;
                    }
                });
    }
}
