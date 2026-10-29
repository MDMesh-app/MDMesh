package com.hmdm.security.jwt;

import com.hmdm.auth.LocalAuth;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.rest.json.UserCredentials;
import com.hmdm.rest.resource.AuthResource;
import com.hmdm.security.LoginThrottle;
import com.hmdm.security.jwt.rest.JWTAuthResource;
import com.hmdm.service.EmailService;
import com.hmdm.util.BackgroundTaskRunnerService;
import com.hmdm.util.PasswordUtil;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;

/**
 * The console's and the API's sign-in routes count against one limit: an attacker who alternates them, varies the
 * login's case or uses the e-mail address, and comes from many addresses behind the Docker edge, gets no more checked
 * guesses at an account than the account limit allows, through either route. Both resources resolve the account the
 * real way (UnsecureDAO.findByLoginOrEmail, via LocalAuth for the console).
 */
public class SignInRoutesShareOneLimitTest {

    private static final String RIGHT_MD5 = "5F4DCC3B5AA765D61D8327DEB882CF99";
    private static final String WRONG_MD5 = "0123456789ABCDEF0123456789ABCDEF";

    @Test
    public void alternatingRoutesSpellingsAndAddressesGetNoMoreThanTheAccountLimit() {
        UnsecureDAO dao = new UnsecureDAO(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, ".", 2, "com.hmdm.launcher") {
            @Override
            public User findByLoginOrEmail(String login) {   // lower(login) = lower(?), then lower(email) = lower(?)
                String l = login.toLowerCase(Locale.ROOT);
                return l.equals("admin") || l.equals("admin@example.com") ? admin() : null;
            }
        };
        LoginThrottle shared = new LoginThrottle("", true, JWTTwoFactorTest.SECRET, "http://localhost");
        BackgroundTaskRunnerService tasks = new BackgroundTaskRunnerService() {
            @Override
            public void submitTask(Runnable task) {
            }
        };
        AuthResource console = new AuthResource(dao, null, dao, tasks,
                new EmailService("", 25, false, false, "", "", "", "", "mdm@localhost", "", "", "", ""),
                null, false, new LocalAuth(dao), shared);
        JWTAuthResource api = new JWTAuthResource(new TokenProvider(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                        + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "3600", "3600"),
                dao, null, tasks, shared);

        Random r = new Random(9);
        int checked = 0;
        for (int i = 0; i < 400; i++) {
            String[] spellings = {"admin", "Admin", "aDMIN", "Admin@Example.com", "admin@EXAMPLE.com"};
            String login = spellings[r.nextInt(spellings.length)];
            HttpServletRequest req = request("172.18.0.4", "203.0." + (i % 250) + "." + r.nextInt(256));
            UserCredentials c = new UserCredentials();
            c.setLogin(login);
            c.setPassword(WRONG_MD5);
            if (r.nextBoolean()) {
                com.hmdm.rest.json.Response res = console.login(c, req, response());
                if (!AuthResource.TOO_MANY_ATTEMPTS.equals(res.getMessage())) {
                    checked++;
                }
            } else if (api.login(c, req).getStatus() != 429) {
                checked++;
            }
        }
        assertEquals("checked guesses through both routes together", 20, checked);

        // And the right password is refused for a new browser too, through either route.
        UserCredentials right = new UserCredentials();
        right.setLogin("admin");
        right.setPassword(RIGHT_MD5);
        assertEquals(429, api.login(right, request("172.18.0.4", "198.51.100.1")).getStatus());
        assertEquals(AuthResource.TOO_MANY_ATTEMPTS,
                console.login(right, request("172.18.0.4", "198.51.100.2"), response()).getMessage());
    }

    private static User admin() {
        UserRole role = new UserRole();
        role.setId(2);
        User u = new User();
        u.setId(1);
        u.setLogin("admin");
        u.setEmail("admin@example.com");
        u.setCustomerId(1);
        u.setUserRole(role);
        u.setPassword(PasswordUtil.getHashFromMd5(RIGHT_MD5));
        u.setAuthToken("token");
        return u;
    }

    private static HttpServletRequest request(String peer, String xff) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getRemoteAddr": return peer;
                        case "getHeaders": return "X-Forwarded-For".equals(a[0])
                                ? Collections.enumeration(Collections.singletonList(xff))
                                : Collections.emptyEnumeration();
                        case "getHeader": return "X-Forwarded-For".equals(a[0]) ? xff : null;
                        default: return null;
                    }
                });
    }

    private static HttpServletResponse response() {
        return (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (p, m, a) -> null);
    }
}
