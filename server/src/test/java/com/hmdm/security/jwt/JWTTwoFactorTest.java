package com.hmdm.security.jwt;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Settings;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.rest.json.UserCredentials;
import com.hmdm.security.LoginThrottle;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.jwt.rest.JWTAuthResource;
import com.hmdm.util.BackgroundTaskRunnerService;
import com.hmdm.util.PasswordUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Token sign-in and token use for accounts whose customer requires two-factor authentication. */
public class JWTTwoFactorTest {

    private static final String PASSWORD_MD5 = "5F4DCC3B5AA765D61D8327DEB882CF99";
    private static final int PLAIN_CUSTOMER = 1;
    private static final int TWO_FACTOR_CUSTOMER = 2;

    private final Map<String, User> users = new HashMap<>();
    private TokenProvider tokenProvider;
    private UnsecureDAO unsecureDAO;
    private final LoginThrottle throttle = new LoginThrottle("", false, SECRET, "http://localhost");
    static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Before
    public void setUp() {
        tokenProvider = new TokenProvider(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "3600", "3600");
        unsecureDAO = new UnsecureDAO(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, ".", 2, "com.hmdm.launcher") {
            @Override
            public User findByLoginOrEmail(String login) {
                User u = users.get(login);
                return u == null ? null : copy(u);
            }

            @Override
            public Settings getSettings(int customerId) {
                Settings settings = new Settings();
                settings.setTwoFactor(customerId == TWO_FACTOR_CUSTOMER);
                return settings;
            }

            @Override
            public void setUserNewPasswordUnsecure(User user) {
            }

            @Override
            public void setUserLoginFailTime(User user, long ts) {
            }
        };
        users.put("plain", user(1, "plain", PLAIN_CUSTOMER));
        users.put("guarded", user(2, "guarded", TWO_FACTOR_CUSTOMER));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    // ---- POST /public/jwt/login --------------------------------------------------------------------------------------

    @Test
    public void passwordAloneDoesNotGetTokenWhenTwoFactorIsOn() {
        Response response = login("guarded", PASSWORD_MD5);

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        assertNull(response.getEntity());
        assertNull(response.getHeaderString("Authorization"));
        assertNull("the password alone doesn't make a client known", response.getHeaderString("Set-Cookie"));
    }

    @Test
    public void passwordGetsTokenWhenTwoFactorIsOff() {
        Response response = login("plain", PASSWORD_MD5);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertTrue(response.getHeaderString("Authorization").startsWith("Bearer "));
    }

    @Test
    public void wrongPasswordStillUnauthorized() {
        Response response = login("guarded", "0123456789ABCDEF0123456789ABCDEF");

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
    }

    @Test
    public void tooManyFailedSignInsAnswer429WithRetryAfterAtOnce() {
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            assertEquals(401, login("plain", "0123456789ABCDEF0123456789ABCDEF", "203.0.113.7").getStatus());
        }
        assertTrue("no failed sign-in sleeps", System.nanoTime() - start < 2_000_000_000L);

        Response refused = login("plain", PASSWORD_MD5, "203.0.113.7");
        assertEquals("the right password is refused too", 429, refused.getStatus());
        assertEquals("always the full window", "900", refused.getHeaderString("Retry-After"));
        assertNull(refused.getHeaderString("Authorization"));

        assertEquals("another client still signs in", 200, login("plain", PASSWORD_MD5, "198.51.100.1").getStatus());
    }

    @Test
    public void unknownLoginsAreLimitedLikeKnownOnes() {
        for (int i = 0; i < 10; i++) {
            assertEquals(401, login("nobody", PASSWORD_MD5, "203.0.113.7").getStatus());
        }
        assertEquals(429, login("nobody", PASSWORD_MD5, "203.0.113.7").getStatus());
    }

    @Test
    public void anApiClientThatKeepsTheCookieOrItsHostKeepsSigningInDuringAnAttack() {
        Response first = login("plain", PASSWORD_MD5, "198.51.100.1", null);
        assertEquals(200, first.getStatus());
        String setCookie = first.getHeaderString("Set-Cookie");
        assertTrue(setCookie, setCookie.startsWith(LoginThrottle.KNOWN_BROWSER_COOKIE + "="));
        assertTrue(setCookie, setCookie.contains("; Path=/rest/public;"));
        String cookie = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));

        for (int i = 0; i < 40; i++) {
            login("plain", "0123456789ABCDEF0123456789ABCDEF", "203.0.113." + i, null);
        }
        assertEquals("a new client on a new host", 429, login("plain", PASSWORD_MD5, "198.51.100.2", null).getStatus());
        assertEquals("the same host, no cookie jar", 200, login("plain", PASSWORD_MD5, "198.51.100.1", null)
                .getStatus());
        assertEquals("the cookie, from a new host", 200, login("plain", PASSWORD_MD5, "198.51.100.3", cookie)
                .getStatus());
    }

    // ---- JWTFilter on /rest/private/* --------------------------------------------------------------------------------

    @Test
    public void filterRejectsTokenOfTwoFactorAccount() throws Exception {
        // e.g. a token issued before two-factor authentication was turned on for the customer
        String token = tokenProvider.createToken(users.get("guarded"), false);

        FilterResult result = filter(token);

        assertEquals(Integer.valueOf(403), result.error);
        assertTrue(result.chainUsers.isEmpty());
    }

    @Test
    public void filterAcceptsTokenOfPlainAccount() throws Exception {
        String token = tokenProvider.createToken(users.get("plain"), false);

        FilterResult result = filter(token);

        assertNull(result.error);
        assertEquals(1, result.chainUsers.size());
        assertEquals("plain", result.chainUsers.get(0).getLogin());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private Response login(String login, String passwordMd5) {
        return login(login, passwordMd5, null);
    }

    private static HttpServletRequest request(String peer, String cookie) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> {
                    if (m.getName().equals("getRemoteAddr")) {
                        return peer;
                    }
                    if (m.getName().equals("getHeaders")) {
                        return Collections.emptyEnumeration();
                    }
                    if (m.getName().equals("getCookies") && cookie != null) {
                        return new javax.servlet.http.Cookie[]{
                                new javax.servlet.http.Cookie(LoginThrottle.KNOWN_BROWSER_COOKIE, cookie)};
                    }
                    return null;
                });
    }

    private Response login(String login, String passwordMd5, String peer) {
        return login(login, passwordMd5, peer, null);
    }

    private Response login(String login, String passwordMd5, String peer, String cookie) {
        BackgroundTaskRunnerService taskRunner = new BackgroundTaskRunnerService() {
            @Override
            public void submitTask(Runnable task) {
            }
        };
        JWTAuthResource resource = new JWTAuthResource(tokenProvider, unsecureDAO, null, taskRunner, throttle);
        UserCredentials credentials = new UserCredentials();
        credentials.setLogin(login);
        credentials.setPassword(passwordMd5);
        return resource.login(credentials, peer == null ? null : request(peer, cookie));
    }

    private static final class FilterResult {
        Integer error;
        final List<User> chainUsers = new ArrayList<>();
    }

    private FilterResult filter(String token) throws Exception {
        FilterResult result = new FilterResult();
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> "getHeader".equals(method.getName()) && "Authorization".equals(args[0])
                        ? "Bearer " + token : null);
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    if ("sendError".equals(method.getName())) {
                        result.error = (Integer) args[0];
                    }
                    return null;
                });
        FilterChain chain = (req, res) -> result.chainUsers.add(SecurityContext.get().getCurrentUser().get());

        new JWTFilter(tokenProvider, unsecureDAO).doFilter(request, response, chain);
        return result;
    }

    private static User user(int id, String login, int customerId) {
        UserRole role = new UserRole();
        role.setId(2);
        User user = new User();
        user.setId(id);
        user.setLogin(login);
        user.setCustomerId(customerId);
        user.setUserRole(role);
        user.setPassword(PasswordUtil.getHashFromMd5(PASSWORD_MD5));
        user.setAuthToken("token-" + id);
        return user;
    }

    private static User copy(User source) {
        User user = user(source.getId(), source.getLogin(), source.getCustomerId());
        user.setAuthToken(source.getAuthToken());
        return user;
    }
}
