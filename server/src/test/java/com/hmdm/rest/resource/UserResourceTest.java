package com.hmdm.rest.resource;

import com.hmdm.persistence.CommonDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.domain.Settings;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.persistence.domain.UserRolePermission;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.rest.json.CurrentPassword;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.rest.json.Response;
import com.hmdm.security.LoginThrottle;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.FakeSessions;
import javax.servlet.http.HttpServletRequest;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * User administration and profile updates (PUT /private/users, PUT /private/users/details), against an in-memory
 * user mapper (Mockito 1.x cannot proxy on JDK 17).
 */
public class UserResourceTest {

    private static final int MASTER = 1;
    private static final int TENANT = 2;
    private static final int OTHER_TENANT = 3;

    private static final String PASSWORD_MD5 = "5F4DCC3B5AA765D61D8327DEB882CF99";
    /** bcrypt: hashed once, not for every fixture user. */
    private static final String PASSWORD_HASH = PasswordUtil.getHashFromMd5(PASSWORD_MD5);

    private static final UserRole SUPER_ADMIN = role(1, true);
    private static final UserRole ORG_ADMIN = role(2, false);
    private static final UserRole USER = role(3, false);

    /** Users keyed by id, and every write the mapper received. */
    private final Map<Integer, User> users = new LinkedHashMap<>();
    private final List<String> writes = new ArrayList<>();
    private final List<User> written = new ArrayList<>();
    private final List<String> accessWrites = new ArrayList<>();
    /** Device groups and configurations by id: the customer each belongs to. */
    private final Map<Integer, Integer> groupCustomer = new LinkedHashMap<>();
    private final Map<Integer, Integer> configurationCustomer = new LinkedHashMap<>();

    private UserResource resource;
    /** Runs when the DAO locks an account's row: what another admin changed between the resource's check and then. */
    private Runnable beforeLock;
    /** Runs at the next findById: what another request did while this one was between its reads. */
    private Runnable atNextRead;
    private UserDAO userDAO;
    private LoginThrottle throttle;
    /** What CustomerMapper.isMultiTenant answers: false = a single-customer (private-mode) install. */
    private boolean multiTenant = false;

    private User superAdmin;
    private User masterOrgAdmin;
    private User orgAdmin;
    private User tenantUser;
    private User otherTenantUser;

    @Before
    public void setUp() {
        UserMapper mapper = fakeMapper();
        userDAO = new UserDAO(mapper, ORG_ADMIN.getId());
        com.hmdm.testsupport.Fakes.FakeMapper<com.hmdm.persistence.mapper.CustomerMapper> customers =
                new com.hmdm.testsupport.Fakes.FakeMapper<>(com.hmdm.persistence.mapper.CustomerMapper.class);
        customers.answer("isMultiTenant", a -> multiTenant);
        UnsecureDAO unsecureDAO = new UnsecureDAO(null, mapper, null, null, null, null, null, null, null, null,
                null, null, customers.mapper, null, ".", ORG_ADMIN.getId(), "com.hmdm.launcher");
        CommonDAO settingsDAO = new CommonDAO(null, null, null) {
            @Override
            public Settings getSettings() {
                return new Settings();
            }
        };
        throttle = new LoginThrottle("", false, "0123456789abcdef0123456789abcdef0123456789abcdef", "");
        resource = new UserResource(userDAO, settingsDAO, unsecureDAO, throttle);

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

    @Test
    public void managementCapabilitiesFollowTheServerRules() {
        signIn(tenantUser);
        assertEquals(Boolean.FALSE, ((Map<?, ?>) resource.management().getData()).get("users"));
        signIn(orgAdmin);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) resource.management().getData()).get("users"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) resource.management().getData()).get("roles"));
        multiTenant = true;
        assertEquals(Boolean.FALSE, ((Map<?, ?>) resource.management().getData()).get("roles"));
        signIn(superAdmin);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) resource.management().getData()).get("roles"));
    }

    @Test
    public void editingAnAccountWithoutAPasswordPreservesItsPendingReset() {
        tenantUser.setPasswordReset(true);
        signIn(orgAdmin);
        assertEquals(Response.ResponseStatus.OK, resource.updateUser(edit(tenantUser, USER), null).getStatus());
        assertTrue(written.get(0).isPasswordReset());
    }

    // ---- account secrets ----------------------------------------------------------------------------------------------

    @Test
    public void aDetailsUpdateNeverWritesAccountSecretsFromTheRequest() {
        // GET hides them, so a GET -> PUT round trip would otherwise clear (or let the client set) them.
        String sql = com.hmdm.testsupport.Fakes.sqlOf(UserMapper.class, "updateUserMainDetails").toLowerCase();
        for (String column : new String[]{"twofactorsecret", "twofactoraccepted", "authdata", "authtoken",
                "passwordresettoken", "password="}) {
            assertTrue(column + " must not be written by updateUserMainDetails: " + sql, !sql.contains(column));
        }
    }

    // ---- PUT /private/users: device and configuration access ---------------------------------------------------------

    /** The org admin limited to device group 5 and configuration 7 of their customer. */
    private void limitOrgAdmin() {
        groupCustomer.put(5, TENANT);
        groupCustomer.put(6, TENANT);
        groupCustomer.put(9, OTHER_TENANT);
        configurationCustomer.put(7, TENANT);
        configurationCustomer.put(8, TENANT);
        configurationCustomer.put(19, OTHER_TENANT);
        orgAdmin.setAllDevicesAvailable(false);
        orgAdmin.setGroups(new ArrayList<>(java.util.Collections.singletonList(new LookupItem(5, "five"))));
        orgAdmin.setAllConfigAvailable(false);
        orgAdmin.setConfigurations(new ArrayList<>(java.util.Collections.singletonList(new LookupItem(7, "seven"))));
    }

    private static User limited(User user, int group, int configuration) {
        user.setAllDevicesAvailable(false);
        user.setGroups(new ArrayList<>(java.util.Collections.singletonList(new LookupItem(group, null))));
        user.setAllConfigAvailable(false);
        user.setConfigurations(new ArrayList<>(java.util.Collections.singletonList(new LookupItem(configuration, null))));
        return user;
    }

    @Test
    public void aLimitedAdminCannotCreateAUserReachingAllDevices() {
        limitOrgAdmin();
        signIn(orgAdmin);

        User all = limited(newUser("fleet", USER), 5, 7);
        all.setAllDevicesAvailable(true);
        assertDenied(resource.updateUser(all, null));

        User allConfigs = limited(newUser("fleet2", USER), 5, 7);
        allConfigs.setAllConfigAvailable(true);
        assertDenied(resource.updateUser(allConfigs, null));
        assertTrue(writes.isEmpty());
    }

    @Test
    public void aLimitedAdminCannotWidenAUserToAllDevices() {
        limitOrgAdmin();
        limited(tenantUser, 5, 7);
        signIn(orgAdmin);

        User edit = limited(edit(tenantUser, USER), 5, 7);
        edit.setAllDevicesAvailable(true);
        assertDenied(resource.updateUser(edit, null));
        assertTrue(writes.isEmpty());
    }

    @Test
    public void aLimitedAdminGrantsOnlyTheirOwnGroupsAndConfigurations() {
        limitOrgAdmin();
        signIn(orgAdmin);

        assertDenied(resource.updateUser(limited(newUser("g6", USER), 6, 7), null));      // a group not theirs
        assertDenied(resource.updateUser(limited(newUser("c8", USER), 5, 8), null));      // a configuration not theirs
        assertDenied(resource.updateUser(limited(newUser("g9", USER), 9, 7), null));      // another customer's group
        assertTrue(writes.isEmpty());

        assertEquals(Response.ResponseStatus.OK, resource.updateUser(limited(newUser("ok", USER), 5, 7), null).getStatus());
        assertEquals("insert", writes.get(0));
    }

    @Test
    public void theCallersAccessIsReadFromTheDatabaseNotTheSession() {
        signIn(orgAdmin);                       // the session still says all devices
        limitOrgAdmin();                        // since limited by a super-admin

        User all = newUser("fleet", USER);      // all devices, all configurations
        assertDenied(resource.updateUser(all, null));
        assertTrue(writes.isEmpty());
    }

    @Test
    public void aLimitedAdminCannotAdministerAnAccountReachingMore() throws Exception {
        // tenantUser reaches all devices and configurations; the admin only group 5 and configuration 7
        limitOrgAdmin();
        signIn(orgAdmin);

        User rename = edit(tenantUser, USER);
        rename.setName("renamed");
        assertDenied(resource.updateUser(rename, null));

        User raised = edit(tenantUser, ORG_ADMIN);     // keeps its access, gets the admin role and a password
        raised.setNewPassword(PASSWORD_MD5);
        assertDenied(resource.updateUser(raised, null));

        assertDenied(resource.updateUser(limited(edit(tenantUser, USER), 6, 8), null));   // narrowing it too
        assertDenied(resource.updateUserDetails(details(tenantUser.getId(), "mine@example.com"), null));
        assertDenied(resource.deleteUser(tenantUser.getId(), confirmed(), null));
        List<Object> sessionWrites = new ArrayList<>();
        assertDenied(resource.login(tenantUser.getId(), request(sessionWrites), null));
        assertTrue("no session as the account", sessionWrites.isEmpty());
        assertTrue(writes.toString(), writes.isEmpty());

        // Wider by groups alone: group 6 is not the admin's
        limited(tenantUser, 6, 7);
        assertDenied(resource.deleteUser(tenantUser.getId(), confirmed(), null));
        assertDenied(resource.login(tenantUser.getId(), request(sessionWrites), null));
        limited(tenantUser, 5, 8);                      // or by configurations alone
        assertDenied(resource.deleteUser(tenantUser.getId(), confirmed(), null));
        assertTrue(writes.toString(), writes.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theListSaysWhichAccountsTheCallerCanAdminister() {
        limitOrgAdmin();
        limited(otherInTenant(), 5, 7);                  // within the admin's scope; tenantUser reaches everything
        signIn(orgAdmin);

        Map<String, Boolean> editable = new LinkedHashMap<>();
        for (User u : (List<User>) resource.getUsers(null).getData()) {
            editable.put(u.getLogin(), u.isEditable());
        }
        assertEquals(Boolean.FALSE, editable.get(orgAdmin.getLogin()));     // the caller's own: /details and /current
        assertEquals(Boolean.FALSE, editable.get(tenantUser.getLogin()));   // wider than the caller
        assertEquals(Boolean.TRUE, editable.get("inscope"));

    }

    private User otherInTenant() {
        return user(12, "inscope", TENANT, USER);
    }

    @Test
    public void aLimitedAdminManagesAccountsWithinTheirScope() throws Exception {
        limitOrgAdmin();
        limited(tenantUser, 5, 7);
        signIn(orgAdmin);

        User edit = limited(edit(tenantUser, ORG_ADMIN), 5, 7);
        edit.setName("renamed");
        edit.setNewPassword(PASSWORD_MD5);
        edit.setOldPassword(PASSWORD_MD5);
        assertEquals(Response.ResponseStatus.OK, resource.updateUser(edit, null).getStatus());
        assertTrue(writes.toString(), writes.contains("updateUserMainDetails") && writes.contains("updatePassword"));

        User details = details(tenantUser.getId(), tenantUser.getEmail());
        details.setName("New name");
        assertEquals(Response.ResponseStatus.OK, resource.updateUserDetails(details, null).getStatus());

        List<Object> sessionWrites = new ArrayList<>();
        assertEquals(Response.ResponseStatus.OK,
                resource.login(tenantUser.getId(), request(sessionWrites), null).getStatus());
        assertEquals(Response.ResponseStatus.OK, resource.deleteUser(tenantUser.getId(), confirmed(), null).getStatus());
        assertTrue(writes.contains("deleteUser:" + tenantUser.getId()));
    }

    @Test
    public void aNewPasswordThatIsNotAnMd5IsAValidationErrorAndNothingIsWritten() {
        signIn(orgAdmin);
        String tooLong = repeat('A', 100);               // over bcrypt's 72 bytes
        for (String bad : new String[]{tooLong, "not-hex-not-hex-not-hex-not-hex!", PASSWORD_MD5 + "0"}) {
            User create = newUser("bad", USER);
            create.setNewPassword(bad);
            assertEquals("error.password.empty", resource.updateUser(create, null).getMessage());
            User change = edit(tenantUser, USER);
            change.setNewPassword(bad);
            assertEquals("error.password.empty", resource.updateUser(change, null).getMessage());
        }
        assertTrue(writes.toString(), writes.isEmpty());

        signIn(superAdmin);
        User reset = new User();
        reset.setId(tenantUser.getId());
        reset.setNewPassword(tooLong);
        assertEquals("error.password.empty", resource.updatePasswordBySuperAdmin(reset, null).getMessage());
        assertTrue(writes.toString(), writes.isEmpty());
    }

    @Test
    public void aNewAccountWithAnEmptyPasswordGetsThePasswordErrorAndNothingIsWritten() {
        signIn(orgAdmin);
        for (String empty : new String[]{"", null}) {
            User create = newUser("nopass", USER);
            create.setNewPassword(empty);
            assertEquals("error.password.empty", resource.updateUser(create, null).getMessage());
        }
        assertTrue(writes.toString(), writes.isEmpty());
    }

    @Test
    public void anEditWithAnEmptyPasswordKeepsThePassword() {
        signIn(orgAdmin);
        User change = edit(tenantUser, USER);
        change.setNewPassword("");
        assertEquals(Response.ResponseStatus.OK, resource.updateUser(change, null).getStatus());
        assertTrue(writes.toString(), writes.stream().noneMatch(w -> w.startsWith("updatePassword")));
    }

    // ---- changing another account's email or password takes the caller's current password -------------------------------

    private static final String WRONG_MD5 = "00000000000000000000000000000001";

    private void assertPasswordRefused(Response response) {
        assertEquals("error.password.wrong", response.getMessage());
        assertTrue(writes.toString(), writes.isEmpty());
    }

    @Test
    public void aNewPasswordForAnotherAccountTakesTheCallersCurrentPassword() {
        signIn(orgAdmin);
        User change = edit(tenantUser, USER);
        change.setNewPassword(PASSWORD_MD5);
        assertPasswordRefused(resource.updateUser(change, null));
        change.setOldPassword(WRONG_MD5);
        assertPasswordRefused(resource.updateUser(change, null));

        change.setOldPassword(PASSWORD_MD5);
        assertEquals(Response.ResponseStatus.OK, resource.updateUser(change, null).getStatus());
        assertTrue(writes.toString(), writes.contains("updatePassword"));
    }

    @Test
    public void anEmailChangeForAnotherAccountTakesTheCallersCurrentPassword() {
        signIn(orgAdmin);
        User change = edit(tenantUser, USER);
        change.setEmail("changed@example.com");
        assertPasswordRefused(resource.updateUser(change, null));

        change.setOldPassword(PASSWORD_MD5);
        assertEquals(Response.ResponseStatus.OK, resource.updateUser(change, null).getStatus());
        assertEquals("changed@example.com", written.get(0).getEmail());
    }

    @Test
    public void clearingAnotherAccountsEmailTakesTheCallersCurrentPassword() {
        signIn(orgAdmin);
        for (String blank : new String[]{null, "", "  "}) {
            User change = edit(tenantUser, USER);
            change.setEmail(blank);
            assertPasswordRefused(resource.updateUser(change, null));
            User details = details(tenantUser.getId(), blank);
            assertPasswordRefused(resource.updateUserDetails(details, null));
        }
    }

    @Test
    public void aSuperAdminGivesTheirCurrentPasswordToo() {
        signIn(superAdmin);
        User change = edit(tenantUser, USER);
        change.setNewPassword(PASSWORD_MD5);
        assertPasswordRefused(resource.updateUser(change, null));
    }

    @Test
    public void anEditLeavingEmailAndPasswordAloneTakesNoPassword() {
        signIn(orgAdmin);
        User change = edit(tenantUser, ORG_ADMIN);
        change.setName("renamed");
        change.setEmail(" " + tenantUser.getEmail() + " ");
        assertEquals(Response.ResponseStatus.OK, resource.updateUser(change, null).getStatus());

        tenantUser.setEmail(null);                      // no address stored, none sent: nothing changes
        User blank = edit(tenantUser, USER);
        blank.setEmail("");
        assertEquals(Response.ResponseStatus.OK, resource.updateUser(blank, null).getStatus());
    }

    @Test
    public void aTakenEmailIsNotRevealedWithoutTheCurrentPassword() {
        signIn(orgAdmin);
        User change = edit(tenantUser, USER);
        change.setEmail(orgAdmin.getEmail());
        assertPasswordRefused(resource.updateUser(change, null));
    }

    @Test
    public void theSuperAdminPasswordRouteTakesTheCallersCurrentPassword() {
        signIn(superAdmin);
        User reset = new User();
        reset.setId(tenantUser.getId());
        reset.setNewPassword(PASSWORD_MD5);
        assertPasswordRefused(resource.updatePasswordBySuperAdmin(reset, null));

        reset.setOldPassword(PASSWORD_MD5);
        assertEquals(Response.ResponseStatus.OK, resource.updatePasswordBySuperAdmin(reset, null).getStatus());
    }

    @Test
    public void theDaoRefusesARouteThatSkipsTheCheck() {
        signIn(orgAdmin);
        User email = copy(tenantUser, false);
        email.setEmail("changed@example.com");
        try {
            userDAO().updateUserIdentity(email.getId(), null, email.getName(), email.getEmail());
            fail("email written without the current password");
        } catch (com.hmdm.security.SecurityException expected) {
            assertTrue(writes.toString(), writes.isEmpty());
        }
        try {
            userDAO().updatePassword(copy(tenantUser, false));
            fail("password written without the current password");
        } catch (com.hmdm.security.SecurityException expected) {
            assertTrue(writes.toString(), writes.isEmpty());
        }
        signIn(superAdmin);
        try {
            userDAO().updatePasswordBySuperAdmin(copy(tenantUser, false));
            fail("password written without the current password");
        } catch (com.hmdm.security.SecurityException expected) {
            assertTrue(writes.toString(), writes.isEmpty());
        }
    }

    @Test
    public void theDaoLeavesTheCallersOwnAccountToItsOwnRoutes() {
        signIn(orgAdmin);                               // /details and /current check the password themselves
        User own = copy(orgAdmin, false);
        own.setEmail("new@example.com");
        userDAO().updateUserIdentity(own.getId(), null, own.getName(), own.getEmail());
        userDAO().updatePassword(copy(orgAdmin, false));
        assertEquals(2, writes.size());
    }

    private static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(c);
        }
        return b.toString();
    }

    @Test
    public void anAdminReachingAllDevicesGrantsAnyOfTheirCustomersGroups() {
        groupCustomer.put(6, TENANT);
        groupCustomer.put(9, OTHER_TENANT);
        configurationCustomer.put(8, TENANT);
        configurationCustomer.put(19, OTHER_TENANT);
        signIn(orgAdmin);

        assertEquals(Response.ResponseStatus.OK, resource.updateUser(newUser("all", USER), null).getStatus());
        assertEquals(Response.ResponseStatus.OK,
                resource.updateUser(limited(newUser("some", USER), 6, 8), null).getStatus());
        assertDenied(resource.updateUser(limited(newUser("foreign", USER), 9, 8), null));
        assertDenied(resource.updateUser(limited(newUser("foreign2", USER), 6, 19), null));
    }

    @Test
    public void aSuperAdminGrantsAnyAccess() {
        signIn(superAdmin);
        User all = newUser("fleet", USER);
        assertEquals(Response.ResponseStatus.OK, resource.updateUser(all, null).getStatus());
    }

    @Test
    public void aLimitedAdminGetsAccessToTheGroupTheyCreateAndNothingElseIsWritten() {
        limitOrgAdmin();
        signIn(orgAdmin);
        userDAO().grantOwnAccessToNewGroup(42);
        userDAO().grantOwnAccessToNewConfiguration(43);
        assertEquals(java.util.Arrays.asList("insertUserDeviceGroupsAccess:10:[42]",
                "insertUserConfigurationAccess:10:[43]"), writes);
        User session = SecurityContext.get().getCurrentUser().get();
        assertTrue(session.getGroups().stream().anyMatch(g -> g.getId() == 42));
        assertTrue(session.getConfigurations().stream().anyMatch(c -> c.getId() == 43));

        writes.clear();
        signIn(superAdmin);                     // reaches everything: nothing to add
        userDAO().grantOwnAccessToNewGroup(44);
        assertTrue(writes.isEmpty());
    }

    // ---- PUT /private/users: role grants ----------------------------------------------------------------------------

    @Test
    public void orgAdminCannotCreateSuperAdmin() {
        signIn(orgAdmin);

        Response response = resource.updateUser(newUser("newadmin", SUPER_ADMIN), null);

        assertDenied(response);
        assertTrue(writes.isEmpty());
    }

    @Test
    public void orgAdminCannotGrantSuperAdminToOwnUser() {
        signIn(orgAdmin);

        Response response = resource.updateUser(edit(tenantUser, SUPER_ADMIN), null);

        assertDenied(response);
        assertTrue(writes.isEmpty());
    }

    @Test
    public void onlySuperAdminCanGrantSuperAdmin() {
        signIn(superAdmin);

        Response response = resource.updateUser(newUser("root2", SUPER_ADMIN), null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(1, written.size());
        assertEquals(SUPER_ADMIN.getId(), written.get(0).getUserRole().getId());
    }

    @Test
    public void orgAdminCanCreateUserInOwnCustomer() {
        signIn(orgAdmin);
        User request = newUser("newop", USER);
        request.setCustomerId(OTHER_TENANT);

        Response response = resource.updateUser(request, null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals("insert", writes.get(0));
        assertEquals(TENANT, written.get(0).getCustomerId());
    }

    @Test
    public void aNewRestrictedAccountGetsTheSelectedGroupsAndConfigurations() {
        signIn(orgAdmin);
        User request = newUser("newop", USER);
        request.setAllDevicesAvailable(false);
        request.setAllConfigAvailable(false);
        request.setGroups(java.util.Collections.singletonList(new LookupItem(9, "Warehouse")));
        request.setConfigurations(java.util.Collections.singletonList(new LookupItem(4, "Kiosk")));
        groupCustomer.put(9, TENANT);               // the customer's own: an all-access admin may grant them
        configurationCustomer.put(4, TENANT);

        assertEquals(Response.ResponseStatus.OK, resource.updateUser(request, null).getStatus());

        assertEquals(java.util.Arrays.asList("insertUserDeviceGroupsAccess:[9]", "insertUserConfigurationAccess:[4]"),
                accessWrites);
    }

    @Test
    public void aNewUnrestrictedAccountGetsNoAccessLists() {
        signIn(orgAdmin);
        User request = newUser("newop", USER);
        request.setAllDevicesAvailable(true);
        request.setAllConfigAvailable(true);
        request.setGroups(java.util.Collections.singletonList(new LookupItem(9, "Warehouse")));
        request.setConfigurations(java.util.Collections.singletonList(new LookupItem(4, "Kiosk")));

        assertEquals(Response.ResponseStatus.OK, resource.updateUser(request, null).getStatus());

        assertTrue(accessWrites.isEmpty());
    }

    // ---- PUT /private/users: target of an update ---------------------------------------------------------------------

    @Test
    public void orgAdminCannotUpdateUserOfAnotherCustomer() {
        signIn(orgAdmin);
        User request = edit(otherTenantUser, USER);
        request.setCustomerId(TENANT);              // claims to be one of the caller's users
        request.setNewPassword(PASSWORD_MD5);

        Response response = resource.updateUser(request, null);

        assertDenied(response);
        assertTrue(writes.isEmpty());
    }

    @Test
    public void orgAdminCannotResetSuperAdminPassword() {
        signIn(orgAdmin);
        User request = edit(superAdmin, USER);
        request.setCustomerId(TENANT);
        request.setNewPassword(PASSWORD_MD5);

        Response response = resource.updateUser(request, null);

        assertDenied(response);
        assertTrue(writes.isEmpty());
    }

    @Test
    public void orgAdminOfSuperAdminCustomerCannotUpdateSuperAdmin() {
        signIn(masterOrgAdmin);
        User request = edit(superAdmin, SUPER_ADMIN);
        request.setNewPassword(PASSWORD_MD5);

        Response response = resource.updateUser(request, null);

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
        request.setOldPassword(PASSWORD_MD5);

        Response response = resource.updateUser(request, null);

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

        Response response = resource.updateUser(request, null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(OTHER_TENANT, written.get(0).getCustomerId());
        assertEquals(ORG_ADMIN.getId(), written.get(0).getUserRole().getId());
    }

    // ---- PUT /private/users/details ----------------------------------------------------------------------------------

    @Test
    public void userCannotEditAnotherUserOfSameCustomer() {
        signIn(tenantUser);
        User request = details(orgAdmin.getId(), "other@example.com");
        request.setOldPassword(PASSWORD_MD5);      // the caller's own password does not help

        Response response = resource.updateUserDetails(request, null);

        assertDenied(response);
        assertTrue(writes.isEmpty());
        assertEquals("orgadmin@example.com", users.get(orgAdmin.getId()).getEmail());
    }

    @Test
    public void emailChangeRequiresCurrentPassword() {
        signIn(tenantUser);

        Response response = resource.updateUserDetails(details(tenantUser.getId(), "new@example.com"), null);

        assertNotEquals(Response.ResponseStatus.OK, response.getStatus());
        assertTrue(writes.isEmpty());
        assertEquals("operator@example.com", users.get(tenantUser.getId()).getEmail());
    }

    @Test
    public void emailChangeWithWrongPasswordIsRefused() {
        signIn(tenantUser);
        User request = details(tenantUser.getId(), "new@example.com");
        request.setOldPassword("0123456789ABCDEF0123456789ABCDEF");

        Response response = resource.updateUserDetails(request, null);

        assertNotEquals(Response.ResponseStatus.OK, response.getStatus());
        assertTrue(writes.isEmpty());
    }

    @Test
    public void userCanChangeOwnEmailWithCurrentPassword() {
        signIn(tenantUser);
        User request = details(tenantUser.getId(), "new@example.com");
        request.setOldPassword(PASSWORD_MD5);

        Response response = resource.updateUserDetails(request, null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals("new@example.com", users.get(tenantUser.getId()).getEmail());
    }

    @Test
    public void userCanChangeOwnNameWithoutPassword() {
        signIn(tenantUser);
        User request = details(tenantUser.getId(), tenantUser.getEmail());
        request.setName("New Name");

        Response response = resource.updateUserDetails(request, null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals("New Name", users.get(tenantUser.getId()).getName());
    }

    @Test
    public void detailsWithoutIdApplyToCaller() {
        signIn(tenantUser);
        User request = details(null, tenantUser.getEmail());
        request.setName("Self");

        Response response = resource.updateUserDetails(request, null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals("Self", users.get(tenantUser.getId()).getName());
    }

    @Test
    public void orgAdminCanEditOwnCustomerUserDetailsWithOwnPassword() {
        signIn(orgAdmin);
        User request = details(tenantUser.getId(), "moved@example.com");
        request.setOldPassword(PASSWORD_MD5);

        Response response = resource.updateUserDetails(request, null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals("moved@example.com", users.get(tenantUser.getId()).getEmail());
    }

    @Test
    public void orgAdminCannotEditDetailsOfAnotherCustomer() {
        signIn(orgAdmin);
        User request = details(otherTenantUser.getId(), "moved@example.com");
        request.setOldPassword(PASSWORD_MD5);

        Response response = resource.updateUserDetails(request, null);

        assertDenied(response);
        assertTrue(writes.isEmpty());
    }

    // ---- PUT /private/users on the caller's own account --------------------------------------------------------------

    @Test
    public void callerCannotEditOwnAccountThroughUserAdministration() {
        signIn(orgAdmin);
        User request = edit(orgAdmin, ORG_ADMIN);
        request.setEmail("redirected@example.com");
        request.setNewPassword(PASSWORD_MD5);

        Response response = resource.updateUser(request, null);

        assertDenied(response);
        assertTrue(writes.isEmpty());
    }

    // ---- PUT /private/users/current ----------------------------------------------------------------------------------

    @Test
    public void ownPasswordChangeAppliesToCallerWhateverLoginIsSent() {
        signIn(tenantUser);
        User request = new User();
        request.setId(tenantUser.getId());
        request.setLogin(orgAdmin.getLogin());
        request.setOldPassword(PASSWORD_MD5);
        request.setNewPassword("0123456789ABCDEF0123456789ABCDEF");

        Response response = resource.updatePassword(request, null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(java.util.Collections.singletonList("updatePassword"), writes);
        assertEquals(tenantUser.getId(), written.get(0).getId());
    }

    @Test
    public void aLegacyCurrentPasswordIsAcceptedAndStoredAsBcrypt() {
        tenantUser.setPassword(com.hmdm.util.CryptoUtil.getSHA1String(PASSWORD_MD5 + "5YdSYHyg2U"));  // 0.4.x
        signIn(tenantUser);
        User request = new User();
        request.setId(tenantUser.getId());
        request.setOldPassword(PASSWORD_MD5);
        request.setNewPassword("0123456789ABCDEF0123456789ABCDEF");

        Response response = resource.updatePassword(request, null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(java.util.Arrays.asList("upgradePasswordHash", "updatePassword"), writes);
        String stored = written.get(0).getPassword();
        assertTrue(stored, PasswordUtil.isBcrypt(stored));
        assertTrue(PasswordUtil.passwordMatch("0123456789ABCDEF0123456789ABCDEF", stored));
    }

    @Test
    public void ownPasswordChangeRefusesANewPasswordThatIsNoMd5() {
        signIn(tenantUser);
        User request = new User();
        request.setId(tenantUser.getId());
        request.setOldPassword(PASSWORD_MD5);
        request.setNewPassword("plain text");

        Response response = resource.updatePassword(request, null);

        assertEquals("error.password.empty", response.getMessage());
        assertTrue(writes.isEmpty());
    }

    @Test
    public void aSuperAdminPasswordSetRefusesANewPasswordThatIsNoMd5() {
        signIn(superAdmin);
        for (String bad : new String[]{null, "", "plain text", PASSWORD_MD5 + "0"}) {
            User request = new User();
            request.setId(tenantUser.getId());
            request.setNewPassword(bad);
            request.setOldPassword(PASSWORD_MD5);

            Response response = resource.updatePasswordBySuperAdmin(request, null);

            assertEquals(bad, "error.password.empty", response.getMessage());
        }
        assertTrue(writes.isEmpty());
    }

    @Test
    public void aClientThatNeverSentAnMd5GivesItsOwnPasswordToChangeAnotherAccount() {
        String sent = "a client's own password";
        orgAdmin.setPassword(com.hmdm.util.CryptoUtil.getSHA1String(sent + "5YdSYHyg2U"));
        signIn(orgAdmin);
        User change = edit(tenantUser, USER);
        change.setNewPassword("0123456789ABCDEF0123456789ABCDEF");
        change.setOldPassword(sent);
        assertEquals(Response.ResponseStatus.OK, resource.updateUser(change, null).getStatus());
    }

    @Test
    public void ownPasswordChangeEndsOtherSessionsAndKeepsThisOne() {
        signIn(tenantUser);
        User thisSession = copy(tenantUser, false);              // what AuthResource stored at sign-in
        FakeSessions sessions = new FakeSessions();
        sessions.existing("this-one").attributes.put("credentials", thisSession);
        User request = new User();
        request.setId(tenantUser.getId());
        request.setOldPassword(PASSWORD_MD5);
        request.setNewPassword("0123456789ABCDEF0123456789ABCDEF");

        Response response = resource.updatePassword(request, sessions.request());

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        String newToken = written.get(0).getAuthToken();
        assertNotNull(newToken);
        assertNotEquals("every other session and JWT of the old password ends", "token-11", newToken);
        assertEquals("this session moves to the new token", newToken, thisSession.getAuthToken());
        assertEquals(newToken, ((User) sessions.current().attributes.get("credentials")).getAuthToken());
        assertEquals(null, written.get(0).getPasswordResetToken());
    }

    @Test
    public void ownPasswordChangeMovesThisSessionToANewId() {
        signIn(tenantUser);
        User thisSession = copy(tenantUser, false);
        FakeSessions sessions = new FakeSessions();
        FakeSessions.Session before = sessions.existing("before-the-change");
        before.attributes.put("credentials", thisSession);
        User request = new User();
        request.setId(tenantUser.getId());
        request.setOldPassword(PASSWORD_MD5);
        request.setNewPassword("0123456789ABCDEF0123456789ABCDEF");

        Response response = resource.updatePassword(request, sessions.request());

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertTrue("the old id ends", before.invalidated);
        FakeSessions.Session after = sessions.current();
        assertNotEquals("before-the-change", after.id);
        User kept = (User) after.attributes.get("credentials");
        assertEquals("still signed in, as the same user", tenantUser.getId(), kept.getId());
        assertEquals("with the new token", written.get(0).getAuthToken(), kept.getAuthToken());
    }

    @Test
    public void aWrongCurrentPasswordKeepsTheSessionId() {
        signIn(tenantUser);
        FakeSessions sessions = new FakeSessions();
        FakeSessions.Session before = sessions.existing("before-the-change");
        before.attributes.put("credentials", copy(tenantUser, false));
        User request = new User();
        request.setId(tenantUser.getId());
        request.setOldPassword("0123456789ABCDEF0123456789ABCDEF");
        request.setNewPassword("0123456789ABCDEF0123456789ABCDEF");

        resource.updatePassword(request, sessions.request());

        assertTrue(!before.invalidated);
        assertEquals(0, sessions.created);
    }

    @Test
    public void ownPasswordChangeWithAWrongPasswordKeepsEverything() {
        signIn(tenantUser);
        User thisSession = copy(tenantUser, false);
        List<Object> sessionWrites = new ArrayList<>();
        User request = new User();
        request.setId(tenantUser.getId());
        request.setOldPassword("0123456789ABCDEF0123456789ABCDEF");
        request.setNewPassword("0123456789ABCDEF0123456789ABCDEF");

        resource.updatePassword(request, request(sessionWrites, thisSession));

        assertTrue(writes.isEmpty());
        assertTrue(sessionWrites.isEmpty());
        assertEquals("token-11", thisSession.getAuthToken());
    }

    // ---- the current-password checks count against the sign-in limit ---------------------------------------------------

    // ---- /details writes name and email only; one email rule; impersonated sessions ----------------------------------

    @Test
    public void anAccountWidenedAfterTheResourceCheckedItIsRefusedAtTheWrite() {
        // The limited admin passes getManagedUser; another admin then gives the account all devices
        for (int route = 0; route < 4; route++) {
            limitOrgAdmin();
            limited(tenantUser, 5, 7);
            signIn(orgAdmin);
            beforeLock = () -> tenantUser.setAllDevicesAvailable(true);
            Response response;
            if (route == 0) {
                User rename = limited(edit(tenantUser, USER), 5, 7);
                rename.setName("renamed");
                response = resource.updateUser(rename, null);
            } else if (route == 1) {
                User password = limited(edit(tenantUser, USER), 5, 7);
                password.setNewPassword(PASSWORD_MD5);
                password.setOldPassword(PASSWORD_MD5);
                response = resource.updateUser(password, null);
            } else if (route == 2) {
                response = resource.deleteUser(tenantUser.getId(), confirmed(), null);
            } else {
                User details = details(tenantUser.getId(), tenantUser.getEmail());
                details.setName("renamed");
                response = resource.updateUserDetails(details, null);
            }
            assertDenied(response);
            assertTrue("route " + route + ": " + writes, writes.isEmpty());
            assertTrue(accessWrites.isEmpty());
        }
    }

    @Test
    public void aDetailsEditNeverRewritesTheAccountsAccess() {
        // Its access read at the start would overwrite a demotion made meanwhile
        limited(tenantUser, 5, 7);
        signIn(orgAdmin);
        User details = details(tenantUser.getId(), tenantUser.getEmail());
        details.setName("Renamed");
        assertEquals(Response.ResponseStatus.OK, resource.updateUserDetails(details, null).getStatus());
        assertEquals(java.util.Collections.singletonList("updateIdentity"), writes);
        assertTrue(accessWrites.toString(), accessWrites.isEmpty());
    }

    @Test
    public void aDetailsEditKeepsADemotionMadeAfterItReadTheAccount() {
        // The account is read before the lock; an admin demotes it in between. The write must not undo that.
        for (boolean self : new boolean[]{true, false}) {
            tenantUser.setUserRole(ORG_ADMIN);
            tenantUser.setAllDevicesAvailable(true);
            tenantUser.setAllConfigAvailable(true);
            writes.clear();
            written.clear();
            signIn(self ? tenantUser : superAdmin);
            beforeLock = () -> {
                tenantUser.setUserRole(USER);
                tenantUser.setAllDevicesAvailable(false);
                tenantUser.setAllConfigAvailable(false);
            };
            User details = details(tenantUser.getId(), tenantUser.getEmail());
            details.setName("Renamed");
            assertEquals(Response.ResponseStatus.OK, resource.updateUserDetails(details, null).getStatus());

            assertEquals(java.util.Collections.singletonList("updateIdentity"), writes);
            User write = written.get(0);
            assertEquals("Renamed", write.getName());
            assertEquals(USER.getId(), write.getUserRole().getId());
            assertFalse(write.isAllDevicesAvailable());
            assertFalse(write.isAllConfigAvailable());
        }
    }

    @Test
    public void theIdentityUpdateWritesOnlyLoginNameAndEmail() {
        String sql = com.hmdm.testsupport.Fakes.sqlOf(UserMapper.class, "updateIdentity").toLowerCase();
        for (String column : new String[]{"userroleid", "alldevicesavailable", "allconfigavailable", "passwordreset",
                "password", "authtoken", "twofactor", "authdata"}) {
            assertFalse(column + " must not be written by updateIdentity: " + sql, sql.contains(column));
        }
    }

    @Test
    public void anEmailIsComparedAndStoredTrimmedOnEveryRoute() {
        signIn(orgAdmin);
        User details = details(tenantUser.getId(), "  " + tenantUser.getEmail() + "  ");
        assertEquals(Response.ResponseStatus.OK, resource.updateUserDetails(details, null).getStatus());
        assertEquals(tenantUser.getEmail(), written.get(0).getEmail());

        User edit = edit(tenantUser, USER);
        edit.setEmail(tenantUser.getEmail() + " ");
        assertEquals(Response.ResponseStatus.OK, resource.updateUser(edit, null).getStatus());
        assertEquals(tenantUser.getEmail(), written.get(1).getEmail());
    }

    @Test
    public void aCaseOnlyEmailChangeIsAChangeOnEveryRoute() {
        String upper = tenantUser.getEmail().toUpperCase();
        signIn(orgAdmin);
        assertPasswordRefused(resource.updateUserDetails(details(tenantUser.getId(), upper), null));
        User edit = edit(tenantUser, USER);
        edit.setEmail(upper);
        assertPasswordRefused(resource.updateUser(edit, null));

        // With the password it is stored, and the address being this account's own is no duplicate
        User details = details(tenantUser.getId(), upper);
        details.setOldPassword(PASSWORD_MD5);
        assertEquals(Response.ResponseStatus.OK, resource.updateUserDetails(details, null).getStatus());
        assertEquals(upper, written.get(0).getEmail());

        // The account's own address, in another case, by its owner
        signIn(tenantUser);
        User own = details(tenantUser.getId(), upper.toLowerCase());
        own.setOldPassword(PASSWORD_MD5);
        assertEquals(Response.ResponseStatus.OK, resource.updateUserDetails(own, null).getStatus());
    }

    /** A request in a session an admin opened as tenantUser (POST /users/impersonate). */
    private HttpServletRequest impersonatedRequest() {
        FakeSessions sessions = new FakeSessions();
        FakeSessions.Session session = sessions.existing("as-operator");
        session.attributes.put("credentials", copy(tenantUser, false));
        session.attributes.put("impersonatedBy", orgAdmin.getId());
        return sessions.request();
    }

    @Test
    public void impersonationMarksTheSession() throws Exception {
        signIn(orgAdmin);
        FakeSessions sessions = new FakeSessions();
        sessions.existing("admin");
        assertEquals(Response.ResponseStatus.OK,
                resource.login(tenantUser.getId(), sessions.request(), null).getStatus());
        assertTrue(com.hmdm.security.ConsoleSession.isImpersonated(sessions.request()));
    }

    @Test
    public void anImpersonatedSessionEndsWhenItsAdminMayNoLongerSignInAsTheAccount() throws Exception {
        String[] scenarios = {"unchanged", "account widened", "admin deleted", "admin password changed",
                "admin demoted", "admin narrowed"};
        for (String scenario : scenarios) {
            setUp();
            limitOrgAdmin();
            limited(tenantUser, 5, 7);
            signIn(orgAdmin);
            com.hmdm.testsupport.FakeSessions sessions = new com.hmdm.testsupport.FakeSessions();
            assertEquals(Response.ResponseStatus.OK,
                    resource.login(tenantUser.getId(), sessions.request(), null).getStatus());
            SecurityContext.release();
            switch (scenario) {
                case "account widened": tenantUser.setAllDevicesAvailable(true); break;
                case "admin deleted": users.remove(orgAdmin.getId()); break;
                case "admin password changed": orgAdmin.setAuthToken("token-new"); break;
                case "admin demoted": orgAdmin.setUserRole(USER); break;
                case "admin narrowed": orgAdmin.setGroups(new ArrayList<>()); break;
                default: break;
            }
            int status = authFilter(sessions);
            if ("unchanged".equals(scenario)) {
                assertEquals(scenario, 200, status);
            } else {
                assertEquals(scenario, 403, status);
                assertTrue(scenario, sessions.all.get(sessions.all.size() - 1).invalidated);
            }
        }
    }

    @Test
    public void aSuperAdminsImpersonatedSessionGoesOnWhateverTheAccountIsGiven() throws Exception {
        limited(tenantUser, 5, 7);
        signIn(superAdmin);
        com.hmdm.testsupport.FakeSessions sessions = new com.hmdm.testsupport.FakeSessions();
        assertEquals(Response.ResponseStatus.OK,
                resource.login(tenantUser.getId(), sessions.request(), null).getStatus());
        SecurityContext.release();
        tenantUser.setAllDevicesAvailable(true);
        assertEquals(200, authFilter(sessions));
        superAdmin.setAuthToken("token-new");
        assertEquals(403, authFilter(sessions));
    }

    @Test
    public void anImpersonatedSessionEndedByAnotherRequestMeanwhileIsRefused() throws Exception {
        // Another request failed the check and ended the session after this one read its user from it
        for (String meanwhileAlso : new String[]{"nothing else", "account widened", "admin demoted"}) {
            setUp();
            limitOrgAdmin();
            limited(tenantUser, 5, 7);
            signIn(orgAdmin);
            com.hmdm.testsupport.FakeSessions sessions = new com.hmdm.testsupport.FakeSessions();
            assertEquals(Response.ResponseStatus.OK,
                    resource.login(tenantUser.getId(), sessions.request(), null).getStatus());
            SecurityContext.release();
            com.hmdm.testsupport.FakeSessions.Session session = sessions.current();
            atNextRead = () -> {
                if (meanwhileAlso.equals("admin demoted")) {
                    orgAdmin.setUserRole(USER);
                } else if (meanwhileAlso.equals("account widened")) {
                    tenantUser.setAllDevicesAvailable(true);
                }
                session.proxy.invalidate();
            };
            int[] status = {0};
            new com.hmdm.rest.filter.AuthFilter(userDAO).doFilter(sessions.request(), errors(status),
                    (req, res) -> status[0] = 200);
            assertEquals(meanwhileAlso, 403, status[0]);
        }
    }

    @Test
    public void aSessionEndedBetweenAuthFilterAndTheResourceStillCountsAsSignedInAsAnother() throws Exception {
        // The super-admin signs in as the org admin; that session's sign-out lands after AuthFilter let a request through
        signIn(superAdmin);
        com.hmdm.testsupport.FakeSessions sessions = new com.hmdm.testsupport.FakeSessions();
        assertEquals(Response.ResponseStatus.OK,
                resource.login(orgAdmin.getId(), sessions.request(), null).getStatus());
        SecurityContext.release();
        com.hmdm.testsupport.FakeSessions.Session signedInAs = sessions.current();
        int opened = sessions.created;

        Response[] answers = new Response[2];
        int[] status = {0};
        new com.hmdm.rest.filter.AuthFilter(userDAO).doFilter(sessions.request(), errors(status), (req, res) -> {
            signedInAs.proxy.invalidate();                  // POST /auth/logout, meanwhile
            javax.servlet.http.HttpServletRequest request = (javax.servlet.http.HttpServletRequest) req;
            answers[0] = resource.login(tenantUser.getId(), request, null);
            User email = details(orgAdmin.getId(), "new@example.com");
            email.setOldPassword("0123456789ABCDEF0123456789ABCDEF");
            answers[1] = resource.updateUserDetails(email, request);
        });

        assertDenied(answers[0]);
        assertEquals("no session opened as the account", opened, sessions.created);
        assertEquals(CurrentPasswordCheck.IMPERSONATED, answers[1].getMessage());
        User account = users.get(orgAdmin.getId());
        assertTrue("no failure counted against the account",
                throttle.begin((String) null, account, account.getLogin(), null).allowed());
    }

    @Test
    public void anApiClientsRequestCarryingASignedInAsAnotherCookieRunsAsTheToken() throws Exception {
        // The JWT is the API client's own; the session cookie it also sends was opened by signing in as another account
        signIn(superAdmin);
        com.hmdm.testsupport.FakeSessions sessions = new com.hmdm.testsupport.FakeSessions();
        resource.login(orgAdmin.getId(), sessions.request(), null);
        signIn(tenantUser);                                 // as JWTFilter leaves it
        boolean[] impersonated = {true};
        int[] status = {0};
        new com.hmdm.rest.filter.AuthFilter(userDAO).doFilter(sessions.request(), errors(status), (req, res) -> {
            status[0] = 200;
            impersonated[0] = com.hmdm.security.ConsoleSession.isImpersonated(
                    (javax.servlet.http.HttpServletRequest) req);
        });
        assertEquals(200, status[0]);
        assertFalse(impersonated[0]);
    }

    @Test
    public void markersAlreadyOnTheRequestAreKept() throws Exception {
        // AuthFilter met this request before (a second mapping): what it checked then stands
        com.hmdm.testsupport.FakeSessions sessions = new com.hmdm.testsupport.FakeSessions();
        javax.servlet.http.HttpServletRequest request = sessions.request();
        com.hmdm.testsupport.FakeSessions.Session session = sessions.existing("checked");
        session.attributes.put("impersonatedBy", 5);
        com.hmdm.security.ConsoleSession.Markers checked = com.hmdm.security.ConsoleSession.markers(session.proxy);
        request.setAttribute(com.hmdm.security.ConsoleSession.MARKERS, checked);
        signIn(tenantUser);
        int[] status = {0};
        new com.hmdm.rest.filter.AuthFilter(userDAO).doFilter(request, errors(status), (req, res) -> status[0] = 200);
        assertSame(checked, request.getAttribute(com.hmdm.security.ConsoleSession.MARKERS));
    }

    @Test
    public void aRequestWhoseSessionEndedCannotVouchItWasNotSignedInAsAnother() {
        com.hmdm.testsupport.FakeSessions sessions = new com.hmdm.testsupport.FakeSessions();
        sessions.existing("ended");
        javax.servlet.http.HttpServletRequest request = sessions.request();
        sessions.current().proxy.invalidate();
        assertTrue(com.hmdm.security.ConsoleSession.isImpersonated(request));
        assertFalse(com.hmdm.security.ConsoleSession.isImpersonated(new com.hmdm.testsupport.FakeSessions().request()));
    }

    @Test
    public void anImpersonatedSessionMissingTheAdminsTokenIsRefused() throws Exception {
        signIn(superAdmin);
        com.hmdm.testsupport.FakeSessions sessions = new com.hmdm.testsupport.FakeSessions();
        resource.login(tenantUser.getId(), sessions.request(), null);
        SecurityContext.release();
        sessions.current().attributes.remove("impersonatorToken");
        assertEquals(403, authFilter(sessions));
    }

    @Test
    public void aSessionFromBeforeTheUpdateEndsOnceAndASignInStartsAFreshOne() throws Exception {
        com.hmdm.testsupport.FakeSessions sessions = new com.hmdm.testsupport.FakeSessions();
        com.hmdm.testsupport.FakeSessions.Session old = sessions.existing("from-0.5.0");
        old.attributes.put("credentials", copy(tenantUser, false));   // as Tomcat restores it: no format marker
        assertEquals(403, authFilter(sessions));
        assertTrue(old.invalidated);

        com.hmdm.security.ConsoleSession.start(sessions.request(), copy(tenantUser, false));
        assertEquals(200, authFilter(sessions));
    }

    @Test
    public void aSessionStillMarkedTwofactorIsAnOrdinarySignedInSession() throws Exception {
        // Older releases marked a sign-in "twofactor" when the customer's settings said so, then refused its every
        // request. A session kept across the update may still carry the mark; nothing reads it now.
        com.hmdm.testsupport.FakeSessions sessions = new com.hmdm.testsupport.FakeSessions();
        com.hmdm.security.ConsoleSession.start(sessions.request(), copy(tenantUser, false));
        sessions.current().attributes.put("twofactor", "true");
        javax.servlet.http.HttpServletRequest request = sessions.request();
        javax.servlet.http.HttpServletRequest page = (javax.servlet.http.HttpServletRequest) Proxy.newProxyInstance(
                UserResourceTest.class.getClassLoader(), new Class<?>[]{javax.servlet.http.HttpServletRequest.class},
                (proxy, method, args) -> "getServletPath".equals(method.getName())
                        ? "/rest/private/users/current" : method.invoke(request, args));
        int[] status = {0};
        new com.hmdm.rest.filter.AuthFilter(userDAO).doFilter(page, errors(status), (req, res) -> status[0] = 200);
        assertEquals(200, status[0]);
        assertNotNull("still signed in", sessions.current().attributes.get("credentials"));
    }

    @Test
    public void anApiClientsRequestIsNotTouchedByTheSessionFormat() throws Exception {
        // JWTFilter set the context: AuthFilter lets it through, whatever session the request also carries
        com.hmdm.testsupport.FakeSessions sessions = new com.hmdm.testsupport.FakeSessions();
        sessions.existing("from-0.5.0").attributes.put("credentials", copy(tenantUser, false));
        signIn(tenantUser);
        int[] status = {0};
        new com.hmdm.rest.filter.AuthFilter(userDAO).doFilter(sessions.request(), errors(status),
                (req, res) -> status[0] = 200);
        assertEquals(200, status[0]);
        assertFalse(sessions.all.get(0).invalidated);
    }

    @Test
    public void signingInAsAnotherAccountFromASessionSignedInAsOneIsRefused() throws Exception {
        signIn(superAdmin);
        com.hmdm.testsupport.FakeSessions sessions = new com.hmdm.testsupport.FakeSessions();
        assertEquals(Response.ResponseStatus.OK,
                resource.login(orgAdmin.getId(), sessions.request(), null).getStatus());
        signIn(orgAdmin);                               // now acting as the org admin, in that session
        com.hmdm.testsupport.FakeSessions.Session inner = sessions.current();
        assertDenied(resource.login(tenantUser.getId(), sessions.request(), null));
        assertSame(inner, sessions.current());
        assertEquals(superAdmin.getId(), inner.attributes.get("impersonatedBy"));
    }

    @Test
    public void aDetailsRenameKeepsAnEmailChangedAfterItReadTheAccount() {
        // An admin cuts off a compromised address while the account's owner renames it
        signIn(tenantUser);
        beforeLock = () -> tenantUser.setEmail("replaced@example.com");
        User details = details(tenantUser.getId(), tenantUser.getEmail());
        details.setName("Renamed");
        assertEquals(Response.ResponseStatus.OK, resource.updateUserDetails(details, null).getStatus());
        assertEquals("replaced@example.com", written.get(0).getEmail());
        assertEquals("Renamed", written.get(0).getName());
    }

    @Test
    public void aDetailsRenameOfAnAccountWithNoEmailKeepsOneSetMeanwhile() {
        tenantUser.setEmail("");
        signIn(tenantUser);
        beforeLock = () -> tenantUser.setEmail("set-by-admin@example.com");
        User details = details(tenantUser.getId(), "");
        details.setName("Renamed");
        assertEquals(Response.ResponseStatus.OK, resource.updateUserDetails(details, null).getStatus());
        assertEquals("set-by-admin@example.com", written.get(0).getEmail());
    }

    private static javax.servlet.http.HttpServletResponse errors(int[] status) {
        return (javax.servlet.http.HttpServletResponse) Proxy.newProxyInstance(UserResourceTest.class.getClassLoader(),
                new Class<?>[]{javax.servlet.http.HttpServletResponse.class}, (proxy, method, args) -> {
                    if ("sendError".equals(method.getName())) {
                        status[0] = (Integer) args[0];
                    }
                    return null;
                });
    }

    /** Runs a request of the session through AuthFilter: 200 when it reaches the resource, else the error sent. */
    private int authFilter(com.hmdm.testsupport.FakeSessions sessions) throws Exception {
        int[] status = {0};
        javax.servlet.http.HttpServletResponse response = (javax.servlet.http.HttpServletResponse)
                Proxy.newProxyInstance(UserResourceTest.class.getClassLoader(),
                        new Class<?>[]{javax.servlet.http.HttpServletResponse.class}, (proxy, method, args) -> {
                            if ("sendError".equals(method.getName())) {
                                status[0] = (Integer) args[0];
                            }
                            return null;
                        });
        new com.hmdm.rest.filter.AuthFilter(userDAO).doFilter(sessions.request(), response,
                (req, res) -> status[0] = 200);
        return status[0];
    }

    @Test
    public void anImpersonatedSessionIsRefusedEveryPasswordCheckAndCountsNoFailure() {
        signIn(tenantUser);
        for (int i = 0; i < 40; i++) {
            Response r = resource.updatePassword(passwordChange("0123456789ABCDEF0123456789ABCDE" + (i % 10)),
                    impersonatedRequest());
            assertEquals(CurrentPasswordCheck.IMPERSONATED, r.getMessage());
            User email = details(tenantUser.getId(), "new@example.com");
            email.setOldPassword("0123456789ABCDEF0123456789ABCDEF");
            assertEquals(CurrentPasswordCheck.IMPERSONATED,
                    resource.updateUserDetails(email, impersonatedRequest()).getMessage());
        }
        // Even the right password: the session's user never gave it
        assertEquals(CurrentPasswordCheck.IMPERSONATED,
                resource.updatePassword(passwordChange(PASSWORD_MD5), impersonatedRequest()).getMessage());
        assertTrue(writes.isEmpty());
        User account = users.get(tenantUser.getId());
        assertTrue("no failure counted against the account",
                throttle.begin((String) null, account, account.getLogin(), null).allowed());

        // An impersonated admin session creates and deletes nothing either
        signIn(orgAdmin);
        User create = newUser("newadmin", USER);
        create.setOldPassword(PASSWORD_MD5);
        assertEquals(CurrentPasswordCheck.IMPERSONATED, resource.updateUser(create, impersonatedRequest()).getMessage());
        assertEquals(CurrentPasswordCheck.IMPERSONATED,
                resource.deleteUser(tenantUser.getId(), confirmed(), impersonatedRequest()).getMessage());
        assertTrue(writes.isEmpty());
    }

    private User passwordChange(String oldPassword) {
        User request = new User();
        request.setId(tenantUser.getId());
        request.setOldPassword(oldPassword);
        request.setNewPassword("0123456789ABCDEF0123456789ABCDEF");
        return request;
    }

    @Test
    public void guessingTheCurrentPasswordThroughAPasswordChangeIsLimitedLikeSignIn() {
        signIn(tenantUser);
        int checked = 0;
        for (int i = 0; i < 40; i++) {
            Response r = resource.updatePassword(passwordChange("0123456789ABCDEF0123456789ABCDE" + (i % 10)), null);
            if (!AuthResource.TOO_MANY_ATTEMPTS.equals(r.getMessage())) {
                assertEquals("error.password.wrong", r.getMessage());
                checked++;
            }
        }
        assertEquals("the account's limit", 20, checked);
        assertEquals("the right one is refused too", AuthResource.TOO_MANY_ATTEMPTS,
                resource.updatePassword(passwordChange(PASSWORD_MD5), null).getMessage());
        assertTrue(writes.isEmpty());
        User account = users.get(tenantUser.getId());
        assertTrue("the same counter as sign-in", !throttle.begin((String) null, account, account.getLogin(), null)
                .allowed());
    }

    @Test
    public void guessingTheCurrentPasswordThroughAnEmailChangeIsLimitedLikeSignIn() {
        signIn(tenantUser);
        int checked = 0;
        for (int i = 0; i < 40; i++) {
            User request = details(tenantUser.getId(), "new@example.com");
            request.setOldPassword("0123456789ABCDEF0123456789ABCDEF");
            if (!AuthResource.TOO_MANY_ATTEMPTS.equals(resource.updateUserDetails(request, null).getMessage())) {
                checked++;
            }
        }
        assertEquals(20, checked);
        User request = details(tenantUser.getId(), "new@example.com");
        request.setOldPassword(PASSWORD_MD5);
        assertEquals(AuthResource.TOO_MANY_ATTEMPTS, resource.updateUserDetails(request, null).getMessage());
        assertEquals("operator@example.com", users.get(tenantUser.getId()).getEmail());
    }

    @Test
    public void aRightCurrentPasswordGivesItsSlotBack() {
        signIn(tenantUser);
        for (int i = 0; i < 30; i++) {
            User request = details(tenantUser.getId(), "n" + i + "@example.com");
            request.setOldPassword(PASSWORD_MD5);
            assertEquals(Response.ResponseStatus.OK, resource.updateUserDetails(request, null).getStatus());
        }
    }

    @Test
    public void anAdminPasswordChangeEndsTheUsersSessions() {
        signIn(orgAdmin);
        User request = edit(tenantUser, USER);
        request.setNewPassword(PASSWORD_MD5);
        request.setOldPassword(PASSWORD_MD5);

        resource.updateUser(request, null);

        User passwordWrite = written.get(writes.indexOf("updatePassword"));
        assertNotNull(passwordWrite.getAuthToken());
        assertNotEquals("token-11", passwordWrite.getAuthToken());
    }

    @Test
    public void aSuperAdminPasswordSetIssuesItsOwnTokens() {
        signIn(superAdmin);
        User request = new User();
        request.setId(tenantUser.getId());
        request.setNewPassword(PASSWORD_MD5);
        request.setOldPassword(PASSWORD_MD5);
        request.setAuthToken("chosen-by-the-client");
        request.setPasswordResetToken("chosen-reset-token");

        Response response = resource.updatePasswordBySuperAdmin(request, null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        User write = written.get(writes.indexOf("setNewPassword"));
        assertNotNull(write.getAuthToken());
        assertNotEquals("chosen-by-the-client", write.getAuthToken());
        assertNotEquals("token-11", write.getAuthToken());
        assertEquals(null, write.getPasswordResetToken());
    }

    // ---- DELETE /private/users/other/{id} ----------------------------------------------------------------------------

    @Test
    public void orgAdminCannotDeleteUserOfAnotherCustomer() {
        signIn(orgAdmin);

        assertDenied(resource.deleteUser(otherTenantUser.getId(), confirmed(), null));
        assertTrue(writes.isEmpty());
    }

    @Test
    public void orgAdminOfSuperAdminCustomerCannotDeleteSuperAdmin() {
        signIn(masterOrgAdmin);

        assertDenied(resource.deleteUser(superAdmin.getId(), confirmed(), null));
        assertTrue(writes.isEmpty());
    }

    @Test
    public void orgAdminCanDeleteOwnCustomerUser() {
        signIn(orgAdmin);

        Response response = resource.deleteUser(tenantUser.getId(), confirmed(), null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertEquals(java.util.Collections.singletonList("deleteUser:" + tenantUser.getId()), writes);
    }

    // ---- creating and deleting an account take the caller's current password ------------------------------------------

    @Test
    public void creatingAnAccountTakesTheCallersCurrentPassword() {
        for (User caller : new User[]{orgAdmin, superAdmin}) {
            for (String sent : new String[]{null, "", WRONG_MD5, "not-an-md5"}) {
                signIn(caller);
                User create = newUser("newadmin", ORG_ADMIN);
                create.setOldPassword(sent);
                assertPasswordRefused(resource.updateUser(create, null));
            }
            signIn(caller);                         // a new request: nothing confirmed by the ones before
            User create = newUser("newadmin" + caller.getId(), ORG_ADMIN);
            assertEquals(Response.ResponseStatus.OK, resource.updateUser(create, null).getStatus());
            assertEquals(java.util.Collections.singletonList("insert"), writes);
            writes.clear();
        }
    }

    @Test
    public void deletingAnAccountTakesTheCallersCurrentPassword() {
        for (CurrentPassword sent : new CurrentPassword[]{null, new CurrentPassword(), currentPassword(""),
                currentPassword(WRONG_MD5), currentPassword("not-an-md5")}) {
            signIn(orgAdmin);
            assertPasswordRefused(resource.deleteUser(tenantUser.getId(), sent, null));
        }
        signIn(superAdmin);
        assertPasswordRefused(resource.deleteUser(tenantUser.getId(), currentPassword(WRONG_MD5), null));

        signIn(orgAdmin);
        assertEquals(Response.ResponseStatus.OK, resource.deleteUser(tenantUser.getId(), confirmed(), null).getStatus());
        assertEquals(java.util.Collections.singletonList("deleteUser:" + tenantUser.getId()), writes);
    }

    @Test
    public void anotherAccountsPasswordDoesNotCount() {
        tenantUser.setPassword(PasswordUtil.getHashFromMd5(WRONG_MD5));   // the target's own password
        signIn(orgAdmin);
        assertPasswordRefused(resource.deleteUser(tenantUser.getId(), currentPassword(WRONG_MD5), null));
        User create = newUser("newadmin", ORG_ADMIN);
        create.setOldPassword(WRONG_MD5);
        assertPasswordRefused(resource.updateUser(create, null));
    }

    @Test
    public void deletingYourOwnAccountIsRefusedBeforeAnyPasswordCheck() {
        signIn(orgAdmin);
        Response response = resource.deleteUser(orgAdmin.getId(), null, null);
        assertEquals("Can't remove self", response.getMessage());
        assertTrue(writes.isEmpty());
    }

    @Test
    public void theDaoRefusesACreateOrDeleteThatSkipsTheCheck() {
        for (User caller : new User[]{orgAdmin, superAdmin}) {
            signIn(caller);
            try {
                userDAO().insert(newUser("skipped", ORG_ADMIN));
                fail("account created without the current password");
            } catch (com.hmdm.security.SecurityException expected) {
                assertTrue(writes.toString(), writes.isEmpty());
            }
            try {
                userDAO().deleteUser(tenantUser.getId());
                fail("account deleted without the current password");
            } catch (com.hmdm.security.SecurityException expected) {
                assertTrue(writes.toString(), writes.isEmpty());
            }
        }
    }

    @Test
    public void aConfirmedPasswordLastsOneRequest() {
        signIn(orgAdmin);
        assertEquals(Response.ResponseStatus.OK, resource.deleteUser(tenantUser.getId(), confirmed(), null).getStatus());
        signIn(orgAdmin);                           // the next request has a new SecurityContext
        try {
            userDAO().insert(newUser("later", ORG_ADMIN));
            fail("account created on an earlier request's password");
        } catch (com.hmdm.security.SecurityException expected) {
            assertEquals(java.util.Collections.singletonList("deleteUser:" + tenantUser.getId()), writes);
        }
    }

    // ---- POST /private/users/impersonate/{id} ------------------------------------------------------------------------

    @Test
    public void orgAdminOfSuperAdminCustomerCannotImpersonateSuperAdmin() throws Exception {
        signIn(masterOrgAdmin);
        List<Object> sessionWrites = new ArrayList<>();

        Response response = resource.login(superAdmin.getId(), request(sessionWrites), null);

        assertDenied(response);
        assertTrue(sessionWrites.isEmpty());
    }

    @Test
    public void orgAdminCanImpersonateOwnCustomerUserWithoutSeeingSecrets() throws Exception {
        signIn(orgAdmin);
        List<Object> sessionWrites = new ArrayList<>();

        Response response = resource.login(tenantUser.getId(), request(sessionWrites), null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        // The account, the session format, and who signed in as it with their token then (server-side only)
        assertEquals(4, sessionWrites.size());
        assertNoSecrets(json(response.getData()));
    }

    @Test
    public void impersonationStartsAFreshSession() throws Exception {
        signIn(orgAdmin);
        FakeSessions sessions = new FakeSessions();
        FakeSessions.Session admins = sessions.existing("the-admins");
        admins.attributes.put("credentials", copy(orgAdmin, false));

        Response response = resource.login(tenantUser.getId(), sessions.request(), null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        assertTrue(admins.invalidated);
        FakeSessions.Session after = sessions.current();
        assertNotEquals("the-admins", after.id);
        assertEquals(tenantUser.getId(), ((User) after.attributes.get("credentials")).getId());
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("credentials", "consoleSessionFormat",
                "impersonatedBy", "impersonatorToken")), after.attributes.keySet());
        assertEquals(orgAdmin.getId(), after.attributes.get("impersonatedBy"));
        assertEquals(orgAdmin.getAuthToken(), after.attributes.get("impersonatorToken"));
    }

    // ---- secrets in responses ----------------------------------------------------------------------------------------

    @Test
    public void userDetailsDoNotExposeSecrets() throws Exception {
        signIn(orgAdmin);

        Response response = resource.getUserDetails(tenantUser.getId());

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        String json = json(response.getData());
        assertTrue(json, json.contains("\"login\":\"operator\""));
        assertNoSecrets(json);
    }

    @Test
    public void userListDoesNotExposeSecrets() throws Exception {
        signIn(orgAdmin);

        Response response = resource.getUsers(null);

        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        String json = json(response.getData());
        assertTrue(json, json.contains("\"login\":\"operator\""));
        assertNoSecrets(json);
    }

    @Test
    public void adminCannotReadSuperAdminDetails() {
        signIn(masterOrgAdmin);

        assertDenied(resource.getUserDetails(superAdmin.getId()));
    }

    @Test
    public void adminCannotReadDetailsOfAnotherCustomer() {
        signIn(orgAdmin);

        assertDenied(resource.getUserDetails(otherTenantUser.getId()));
    }

    @Test
    public void theCurrentUserAnswerSaysWhoMayManageTheDeployment() throws Exception {
        // The updater (supervisor/lib.js deploymentAdminVerdict) lets only a session whose GET /private/users/current
        // answer has deploymentAdmin === true apply, roll back or schedule updates.
        multiTenant = false;                      // private mode: one customer, its org admin is the operator
        assertDeploymentAdmin(superAdmin, true);
        assertDeploymentAdmin(masterOrgAdmin, true);
        assertDeploymentAdmin(tenantUser, false);  // a user role, even on a single-customer install

        multiTenant = true;                       // a second customer exists: super-admins only
        assertDeploymentAdmin(superAdmin, true);
        assertDeploymentAdmin(orgAdmin, false);
        assertDeploymentAdmin(masterOrgAdmin, false);
    }

    @Test
    public void aRoleWithTheSettingsPermissionIsNotAnOrgAdmin() throws Exception {
        multiTenant = false;
        User custom = user(30, "custom", MASTER, role(7, false));
        custom.getUserRole().setPermissions(role(2, false).getPermissions());   // holds "settings"
        assertDeploymentAdmin(custom, false);
    }

    private void assertDeploymentAdmin(User who, boolean expected) throws Exception {
        signIn(who);
        String json = json(resource.getCurrentUserDetails().getData());
        assertTrue(who.getLogin() + ": " + json, json.contains("\"deploymentAdmin\":" + expected));
    }

    @Test
    public void passwordResetTokenIsStillAcceptedInRequests() throws Exception {
        // POST /public/passwordReset/reset reads the token from a User body
        User body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"passwordResetToken\":\"abc\",\"newPassword\":\"x\"}", User.class);

        assertEquals("abc", body.getPasswordResetToken());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static String json(Object value) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    }

    private static void assertNoSecrets(String json) {
        for (String field : new String[]{"password", "authToken", "passwordResetToken", "twoFactorSecret", "authData"}) {
            assertTrue(field + " in " + json, !json.contains("\"" + field + "\""));
        }
        assertTrue(json, !json.contains("reset-") && !json.contains("token-") && !json.contains("totp-"));
    }

    /** A request whose session records what is stored in it. */
    private static javax.servlet.http.HttpServletRequest request(List<Object> sessionWrites) {
        return request(sessionWrites, null);
    }

    /** A request whose session holds {@code credentials} and records what is stored in it. */
    private static javax.servlet.http.HttpServletRequest request(List<Object> sessionWrites, User credentials) {
        javax.servlet.http.HttpSession session = (javax.servlet.http.HttpSession) Proxy.newProxyInstance(
                UserResourceTest.class.getClassLoader(), new Class<?>[]{javax.servlet.http.HttpSession.class},
                (proxy, method, args) -> {
                    if ("setAttribute".equals(method.getName())) {
                        sessionWrites.add(args[1]);
                    }
                    if ("getAttribute".equals(method.getName()) && "credentials".equals(args[0])) {
                        return credentials;
                    }
                    return null;
                });
        return (javax.servlet.http.HttpServletRequest) Proxy.newProxyInstance(
                UserResourceTest.class.getClassLoader(), new Class<?>[]{javax.servlet.http.HttpServletRequest.class},
                (proxy, method, args) -> "getSession".equals(method.getName()) ? session : null);
    }

    private UserDAO userDAO() {
        return userDAO;
    }

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
        List<UserRolePermission> permissions = new ArrayList<>();
        if (id == 2) {                                  // the org admin role holds the "settings" permission
            UserRolePermission settings = new UserRolePermission();
            settings.setName("settings");
            permissions.add(settings);
        }
        role.setPermissions(permissions);
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
        user.setPassword(PASSWORD_HASH);
        user.setAuthToken("token-" + id);
        user.setPasswordResetToken("reset-" + id);
        user.setTwoFactorSecret("totp-" + id);
        user.setAuthData("authdata-" + id);
        users.put(id, user);
        return user;
    }

    private static User newUser(String login, UserRole role) {
        User user = new User();
        user.setLogin(login);
        user.setName(login);
        user.setUserRole(role(role.getId(), false));   // the request never tells the server what a role grants
        user.setNewPassword(PASSWORD_MD5);
        user.setOldPassword(PASSWORD_MD5);              // the caller's current password: creating an account takes it
        return user;
    }

    /** A DELETE body with the caller's current password (every fixture user has the same one). */
    private static CurrentPassword confirmed() {
        return currentPassword(PASSWORD_MD5);
    }

    private static CurrentPassword currentPassword(String md5) {
        CurrentPassword body = new CurrentPassword();
        body.setOldPassword(md5);
        return body;
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
        user.setPasswordResetToken(source.getPasswordResetToken());
        user.setPasswordReset(source.isPasswordReset());
        user.setTwoFactorSecret(source.getTwoFactorSecret());
        user.setAuthData(source.getAuthData());
        user.setAllDevicesAvailable(source.isAllDevicesAvailable());
        user.setAllConfigAvailable(source.isAllConfigAvailable());
        user.setGroups(source.getGroups() == null ? new ArrayList<>() : new ArrayList<>(source.getGroups()));
        user.setConfigurations(source.getConfigurations() == null ? new ArrayList<>()
                : new ArrayList<>(source.getConfigurations()));
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
                        case "lockById": {
                            // Another admin's change, committed before this transaction got the row
                            Runnable meanwhile = beforeLock;
                            beforeLock = null;
                            if (meanwhile != null) {
                                meanwhile.run();
                            }
                            return users.containsKey((Integer) args[0]) ? args[0] : null;
                        }
                        case "findById": {
                            Runnable meanwhile = atNextRead;
                            atNextRead = null;
                            if (meanwhile != null) {
                                meanwhile.run();
                            }
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
                        case "findAll":
                        case "findAllByFilter": {
                            int customerId = (Integer) args[0];
                            return users.values().stream().filter(u -> u.getCustomerId() == customerId)
                                    .map(u -> copy(u, false)).collect(Collectors.toList());
                        }
                        case "deleteUser":
                            writes.add("deleteUser:" + ((User) args[0]).getId());
                            return null;
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
                        case "updateIdentity":
                        case "updatePassword":
                        case "setNewPassword":
                            persist(method.getName(), (User) args[0]);
                            return null;
                        case "upgradePasswordHash": {
                            writes.add("upgradePasswordHash");
                            User u = users.get((Integer) args[0]);
                            if (u == null || !u.getPassword().equals(args[1])) {
                                return 0;
                            }
                            u.setPassword((String) args[2]);
                            return 1;
                        }
                        case "removeDeviceGroupsAccessByUserId":
                        case "removeConfigurationAccessByUserId":
                            return null;
                        case "insertUserDeviceGroupsAccess":
                        case "insertUserConfigurationAccess":
                            accessWrites.add(method.getName() + ":" + args[1]);
                            writes.add(method.getName() + ":" + args[0] + ":" + args[1]);
                            return null;
                        case "findCustomerGroupIds":
                        case "findCustomerConfigurationIds": {
                            Map<Integer, Integer> owner = method.getName().equals("findCustomerGroupIds")
                                    ? groupCustomer : configurationCustomer;
                            int customerId = (Integer) args[0];
                            @SuppressWarnings("unchecked") List<Integer> ids = (List<Integer>) args[1];
                            return ids.stream().filter(i -> Integer.valueOf(customerId).equals(owner.get(i)))
                                    .collect(Collectors.toList());
                        }
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
