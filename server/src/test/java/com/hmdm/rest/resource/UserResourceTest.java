package com.hmdm.rest.resource;

import com.hmdm.persistence.CommonDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.domain.Settings;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.PasswordUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * User administration and profile updates (PUT /private/users, PUT /private/users/details), against an in-memory
 * user mapper (Mockito 1.x cannot proxy on JDK 17).
 */
public class UserResourceTest {

    private static final int MASTER = 1;
    private static final int TENANT = 2;
    private static final int OTHER_TENANT = 3;

    private static final String PASSWORD_MD5 = "5F4DCC3B5AA765D61D8327DEB882CF99";

    private static final UserRole SUPER_ADMIN = role(1, true);
    private static final UserRole ORG_ADMIN = role(2, false);
    private static final UserRole USER = role(3, false);

    /** Users keyed by id, and every write the mapper received. */
    private final Map<Integer, User> users = new LinkedHashMap<>();
    private final List<String> writes = new ArrayList<>();
    private final List<User> written = new ArrayList<>();

    private UserResource resource;

    private User superAdmin;
    private User masterOrgAdmin;
    private User orgAdmin;
    private User tenantUser;
    private User otherTenantUser;

    @Before
    public void setUp() {
        UserMapper mapper = fakeMapper();
        UserDAO userDAO = new UserDAO(mapper, ORG_ADMIN.getId());
        UnsecureDAO unsecureDAO = new UnsecureDAO(null, mapper, null, null, null, null, null, null, null, null,
                null, null, null, null, ".", ORG_ADMIN.getId(), "com.hmdm.launcher");
        CommonDAO settingsDAO = new CommonDAO(null, null, null) {
            @Override
            public Settings getSettings() {
                return new Settings();
            }
        };
        resource = new UserResource(userDAO, settingsDAO, unsecureDAO);

        superAdmin = user(1, "admin", MASTER, SUPER_ADMIN);
        masterOrgAdmin = user(2, "masterorgadmin", MASTER, ORG_ADMIN);
        orgAdmin = user(10, "orgadmin", TENANT, ORG_ADMIN);
        tenantUser = user(11, "operator", TENANT, USER);
        otherTenantUser = user(20, "elsewhere", OTHER_TENANT, USER);
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    // ---- PUT /private/users: role grants ----------------------------------------------------------------------------

    @Test
    public void orgAdminCannotCreateSuperAdmin() {
        signIn(orgAdmin);

        Response response = resource.updateUser(newUser("mallory", SUPER_ADMIN));

        assertDenied(response);
        assertTrue(writes.isEmpty());
    }

    @Test
    public void orgAdminCannotGrantSuperAdminToOwnUser() {
        signIn(orgAdmin);

        Response response = resource.updateUser(edit(tenantUser, SUPER_ADMIN));

        assertDenied(response);
        assertTrue(writes.isEmpty());
    }

    @Test
    public void onlySuperAdminCanGrantSuperAdmin() {
        signIn(superAdmin);

        Response response = resource.updateUser(newUser("root2", SUPER_ADMIN));

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(1, written.size());
        assertEquals(SUPER_ADMIN.getId(), written.get(0).getUserRole().getId());
    }

    @Test
    public void orgAdminCanCreateUserInOwnCustomer() {
        signIn(orgAdmin);
        User request = newUser("newop", USER);
        request.setCustomerId(OTHER_TENANT);

        Response response = resource.updateUser(request);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals("insert", writes.get(0));
        assertEquals(TENANT, written.get(0).getCustomerId());
    }

    // ---- PUT /private/users: target of an update ---------------------------------------------------------------------

    @Test
    public void orgAdminCannotUpdateUserOfAnotherCustomer() {
        signIn(orgAdmin);
        User request = edit(otherTenantUser, USER);
        request.setCustomerId(TENANT);              // claims to be one of the caller's users
        request.setNewPassword(PASSWORD_MD5);

        Response response = resource.updateUser(request);

        assertDenied(response);
        assertTrue(writes.isEmpty());
    }

    @Test
    public void orgAdminCannotResetSuperAdminPassword() {
        signIn(orgAdmin);
        User request = edit(superAdmin, USER);
        request.setCustomerId(TENANT);
        request.setNewPassword(PASSWORD_MD5);

        Response response = resource.updateUser(request);

        assertDenied(response);
        assertTrue(writes.isEmpty());
    }

    @Test
    public void orgAdminOfSuperAdminCustomerCannotUpdateSuperAdmin() {
        signIn(masterOrgAdmin);
        User request = edit(superAdmin, SUPER_ADMIN);
        request.setNewPassword(PASSWORD_MD5);

        Response response = resource.updateUser(request);

        assertDenied(response);
        assertTrue(writes.isEmpty());
    }

    @Test
    public void orgAdminCanUpdateAndResetOwnCustomerUser() {
        signIn(orgAdmin);
        User request = edit(tenantUser, USER);
        request.setName("Renamed");
        request.setCustomerId(OTHER_TENANT);        // ignored: the persisted customer wins
        request.setNewPassword(PASSWORD_MD5);

        Response response = resource.updateUser(request);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(java.util.Arrays.asList("updateUserMainDetails", "updatePassword"), writes);
        for (User u : written) {
            assertEquals(tenantUser.getId(), u.getId());
            assertEquals(TENANT, u.getCustomerId());
        }
        assertEquals("Renamed", written.get(0).getName());
    }

    @Test
    public void superAdminCanUpdateUserOfAnyCustomer() {
        signIn(superAdmin);
        User request = edit(otherTenantUser, ORG_ADMIN);

        Response response = resource.updateUser(request);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(OTHER_TENANT, written.get(0).getCustomerId());
        assertEquals(ORG_ADMIN.getId(), written.get(0).getUserRole().getId());
    }

    // ---- PUT /private/users/details ----------------------------------------------------------------------------------

    @Test
    public void userCannotEditAnotherUserOfSameCustomer() {
        signIn(tenantUser);
        User request = details(orgAdmin.getId(), "attacker@example.com");
        request.setOldPassword(PASSWORD_MD5);      // the caller's own password does not help

        Response response = resource.updateUserDetails(request);

        assertDenied(response);
        assertTrue(writes.isEmpty());
        assertEquals("orgadmin@example.com", users.get(orgAdmin.getId()).getEmail());
    }

    @Test
    public void emailChangeRequiresCurrentPassword() {
        signIn(tenantUser);

        Response response = resource.updateUserDetails(details(tenantUser.getId(), "new@example.com"));

        assertNotEquals(Response.ResponseStatus.OK, response.getStatus());
        assertTrue(writes.isEmpty());
        assertEquals("operator@example.com", users.get(tenantUser.getId()).getEmail());
    }

    @Test
    public void emailChangeWithWrongPasswordIsRefused() {
        signIn(tenantUser);
        User request = details(tenantUser.getId(), "new@example.com");
        request.setOldPassword("0123456789ABCDEF0123456789ABCDEF");

        Response response = resource.updateUserDetails(request);

        assertNotEquals(Response.ResponseStatus.OK, response.getStatus());
        assertTrue(writes.isEmpty());
    }

    @Test
    public void userCanChangeOwnEmailWithCurrentPassword() {
        signIn(tenantUser);
        User request = details(tenantUser.getId(), "new@example.com");
        request.setOldPassword(PASSWORD_MD5);

        Response response = resource.updateUserDetails(request);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals("new@example.com", users.get(tenantUser.getId()).getEmail());
    }

    @Test
    public void userCanChangeOwnNameWithoutPassword() {
        signIn(tenantUser);
        User request = details(tenantUser.getId(), tenantUser.getEmail());
        request.setName("New Name");

        Response response = resource.updateUserDetails(request);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals("New Name", users.get(tenantUser.getId()).getName());
    }

    @Test
    public void detailsWithoutIdApplyToCaller() {
        signIn(tenantUser);
        User request = details(null, tenantUser.getEmail());
        request.setName("Self");

        Response response = resource.updateUserDetails(request);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals("Self", users.get(tenantUser.getId()).getName());
    }

    @Test
    public void orgAdminCanEditOwnCustomerUserDetailsWithOwnPassword() {
        signIn(orgAdmin);
        User request = details(tenantUser.getId(), "moved@example.com");
        request.setOldPassword(PASSWORD_MD5);

        Response response = resource.updateUserDetails(request);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals("moved@example.com", users.get(tenantUser.getId()).getEmail());
    }

    @Test
    public void orgAdminCannotEditDetailsOfAnotherCustomer() {
        signIn(orgAdmin);
        User request = details(otherTenantUser.getId(), "moved@example.com");
        request.setOldPassword(PASSWORD_MD5);

        Response response = resource.updateUserDetails(request);

        assertDenied(response);
        assertTrue(writes.isEmpty());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static void assertDenied(Response response) {
        assertNotNull(response);
        assertEquals(Response.ResponseStatus.ERROR, response.getStatus());
        assertEquals(Response.PERMISSION_DENIED().getMessage(), response.getMessage());
    }

    private void signIn(User user) {
        SecurityContext.release();
        SecurityContext.init(copy(user, false));     // as AuthFilter does: the persisted user, without password
    }

    private static UserRole role(int id, boolean superAdmin) {
        UserRole role = new UserRole();
        role.setId(id);
        role.setName("role" + id);
        role.setSuperAdmin(superAdmin);
        return role;
    }

    private User user(int id, String login, int customerId, UserRole role) {
        User user = new User();
        user.setId(id);
        user.setLogin(login);
        user.setName(login);
        user.setEmail(login + "@example.com");
        user.setCustomerId(customerId);
        user.setUserRole(role);
        user.setPassword(PasswordUtil.getHashFromMd5(PASSWORD_MD5));
        user.setAuthToken("token-" + id);
        users.put(id, user);
        return user;
    }

    private static User newUser(String login, UserRole role) {
        User user = new User();
        user.setLogin(login);
        user.setName(login);
        user.setUserRole(role(role.getId(), false));   // the request never tells the server what a role grants
        user.setNewPassword(PASSWORD_MD5);
        return user;
    }

    /** A PUT /private/users body for an existing user, as the console would send it. */
    private static User edit(User target, UserRole role) {
        User user = new User();
        user.setId(target.getId());
        user.setLogin(target.getLogin());
        user.setName(target.getName());
        user.setEmail(target.getEmail());
        user.setCustomerId(target.getCustomerId());
        user.setUserRole(role(role.getId(), false));
        return user;
    }

    private static User details(Integer id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    private static User copy(User source, boolean withPassword) {
        User user = new User();
        user.setId(source.getId());
        user.setLogin(source.getLogin());
        user.setName(source.getName());
        user.setEmail(source.getEmail());
        user.setCustomerId(source.getCustomerId());
        user.setUserRole(source.getUserRole());
        user.setAuthToken(source.getAuthToken());
        user.setAllDevicesAvailable(source.isAllDevicesAvailable());
        user.setAllConfigAvailable(source.isAllConfigAvailable());
        user.setGroups(new ArrayList<>());
        user.setConfigurations(new ArrayList<>());
        if (withPassword) {
            user.setPassword(source.getPassword());
        }
        return user;
    }

    private User byLogin(String login) {
        return users.values().stream().filter(u -> u.getLogin().equalsIgnoreCase(login)).findFirst().orElse(null);
    }

    private User byEmail(String email) {
        return users.values().stream()
                .filter(u -> u.getEmail() != null && u.getEmail().equalsIgnoreCase(email))
                .findFirst().orElse(null);
    }

    /** Persists a write as the SQL would: by id, and only within the customer the statement names. */
    private void persist(String name, User u) {
        writes.add(name);
        written.add(copy(u, true));
        User stored = users.get(u.getId());
        if (stored != null && stored.getCustomerId() == u.getCustomerId()) {
            stored.setName(u.getName());
            stored.setEmail(u.getEmail());
            stored.setUserRole(u.getUserRole());
        }
    }

    /** UserMapper backed by {@link #users}; the find* methods return copies, like a fresh query would. */
    private UserMapper fakeMapper() {
        return (UserMapper) Proxy.newProxyInstance(UserMapper.class.getClassLoader(), new Class<?>[]{UserMapper.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "findById": {
                            User u = users.get((Integer) args[0]);
                            return u == null ? null : copy(u, false);
                        }
                        case "findByLogin": {
                            User u = byLogin((String) args[0]);
                            return u == null ? null : copy(u, true);
                        }
                        case "findByEmail": {
                            User u = byEmail((String) args[0]);
                            return u == null ? null : copy(u, true);
                        }
                        case "findAllUserRoles": {
                            boolean includeSuperAdmin = (Boolean) args[0];
                            List<UserRole> roles = java.util.Arrays.asList(SUPER_ADMIN, ORG_ADMIN, USER);
                            return roles.stream()
                                    .filter(r -> r.isSuperAdmin() == includeSuperAdmin || !r.isSuperAdmin())
                                    .collect(Collectors.toList());
                        }
                        case "insert": {
                            User u = (User) args[0];
                            u.setId(100 + users.size());
                            writes.add("insert");
                            written.add(copy(u, true));
                            return null;
                        }
                        case "updateUserMainDetails":
                        case "updatePassword":
                            persist(method.getName(), (User) args[0]);
                            return null;
                        case "removeDeviceGroupsAccessByUserId":
                        case "removeConfigurationAccessByUserId":
                        case "insertUserDeviceGroupsAccess":
                        case "insertUserConfigurationAccess":
                            return null;
                        case "toString":
                            return "FakeUserMapper";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
