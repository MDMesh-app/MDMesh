package com.hmdm.rest.resource;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.UserRoleDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.persistence.domain.UserRolePermission;
import com.hmdm.persistence.mapper.UserRoleMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * PUT /private/roles: an org admin of a single-customer install edits roles, but only within what they hold: no
 * super-admin role, not the admin role (both protected for everyone, error.role.protected), no super-admin permission
 * (not assignable at all, error.role.permission), and no permission their own role lacks (permission denied).
 */
public class UserRoleGrantTest {

    private static final int SUPER_ROLE = 1;
    private static final int ADMIN_ROLE = 2;
    private static final int USER_ROLE = 3;

    private static final int P_SUPER = 1;      // permissions.superadmin = TRUE
    private static final int P_SETTINGS = 10;
    private static final int P_DEVICES = 11;
    private static final int P_PLUGIN = 50;    // a permission the admin role does not hold

    private final Map<Integer, UserRole> roles = new HashMap<>();
    private Fakes.FakeMapper<UserRoleMapper> mapper;
    private UserRoleResource resource;

    @Before
    public void setUp() {
        roles.put(SUPER_ROLE, role(SUPER_ROLE, true, P_SUPER, P_SETTINGS, P_DEVICES, P_PLUGIN));
        roles.put(ADMIN_ROLE, role(ADMIN_ROLE, false, P_SETTINGS, P_DEVICES));
        roles.put(USER_ROLE, role(USER_ROLE, false, P_DEVICES, P_PLUGIN));
        mapper = new Fakes.FakeMapper<>(UserRoleMapper.class)
                .answer("findById", a -> roles.get((Integer) a[0]))
                .answer("getPermissionsList", a -> Arrays.asList(permission(P_SETTINGS), permission(P_DEVICES),
                        permission(P_PLUGIN)))
                .answer("findByName", a -> null);
        UnsecureDAO unsecure = new UnsecureDAO(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, ".", ADMIN_ROLE, "com.hmdm.launcher") {
            @Override
            public boolean isSingleCustomer() {
                return true;
            }
        };
        resource = new UserRoleResource(new UserRoleDAO(unsecure, new UserDAO(null, ADMIN_ROLE), mapper.mapper,
                ADMIN_ROLE));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void anOrgAdminEditsNoSuperAdminRoleAndNotTheAdminRole() {
        signIn(ADMIN_ROLE);
        assertRefused("error.role.protected", resource.updateRole(request(SUPER_ROLE, P_SETTINGS)));
        assertRefused("error.role.protected", resource.updateRole(request(ADMIN_ROLE, P_SETTINGS, P_DEVICES, P_PLUGIN)));
        assertNoWrites();
    }

    @Test
    public void anOrgAdminGrantsNoSuperAdminPermission() {
        signIn(ADMIN_ROLE);
        assertRefused("error.role.permission", resource.updateRole(request(null, P_SETTINGS, P_SUPER)));
        assertRefused("error.role.permission", resource.updateRole(request(USER_ROLE, P_DEVICES, P_SUPER)));
        assertNoWrites();
    }

    @Test
    public void anOrgAdminGrantsNoPermissionTheyLack() {
        signIn(ADMIN_ROLE);
        assertDenied(resource.updateRole(request(null, P_DEVICES, P_PLUGIN)));
        assertNoWrites();
    }

    @Test
    public void anOrgAdminGrantsWhatTheyHoldAndKeepsWhatARoleHas() {
        signIn(ADMIN_ROLE);
        assertEquals(Response.ResponseStatus.OK, resource.updateRole(request(null, P_SETTINGS, P_DEVICES)).getStatus());
        // USER_ROLE already has P_PLUGIN: saving it again (with P_SETTINGS added) is fine
        assertEquals(Response.ResponseStatus.OK,
                resource.updateRole(request(USER_ROLE, P_DEVICES, P_PLUGIN, P_SETTINGS)).getStatus());
        assertEquals(2, mapper.callsTo("insertPermissions").size());
    }

    @Test
    public void aSuperAdminGrantsAnyAssignablePermission() {
        signIn(SUPER_ROLE);
        assertEquals(Response.ResponseStatus.OK,
                resource.updateRole(request(null, P_SETTINGS, P_PLUGIN)).getStatus());
        assertEquals(1, mapper.callsTo("insertPermissions").size());
    }

    @Test
    public void aRefusedGrantIsPermissionDeniedNotAGenericSaveError() {
        // The DAO's SecurityException reaches the resource as itself, past its catch (Exception)
        signIn(ADMIN_ROLE);
        Response response = resource.updateRole(request(null, P_PLUGIN));
        assertDenied(response);
        assertTrue(!"error.role.save".equals(response.getMessage()));
    }

    private void assertNoWrites() {
        assertTrue(mapper.callsStartingWith("insert", "update", "delete").toString(),
                mapper.callsStartingWith("insert", "update", "delete").isEmpty());
    }

    private static void assertDenied(Response response) {
        assertEquals(Response.ResponseStatus.ERROR, response.getStatus());
        assertEquals(Response.PERMISSION_DENIED().getMessage(), response.getMessage());
    }

    private static void assertRefused(String message, Response response) {
        assertEquals(Response.ResponseStatus.ERROR, response.getStatus());
        assertEquals(message, response.getMessage());
    }

    private void signIn(int roleId) {
        User u = new User();
        u.setId(roleId * 100);
        u.setLogin("u" + roleId);
        u.setCustomerId(1);
        UserRole sessionRole = new UserRole();       // the session's copy; the DAO reads the stored role
        sessionRole.setId(roleId);
        sessionRole.setSuperAdmin(roles.get(roleId).isSuperAdmin());
        sessionRole.setPermissions(new ArrayList<>());
        u.setUserRole(sessionRole);
        SecurityContext.release();
        SecurityContext.init(u);
    }

    private static UserRole request(Integer id, int... permissions) {
        UserRole r = role(id == null ? 0 : id, false, permissions);
        if (id == null) {
            r.setId(null);
        }
        r.setName("role " + id);
        return r;
    }

    private static UserRole role(int id, boolean superAdmin, int... permissions) {
        UserRole r = new UserRole();
        r.setId(id);
        r.setName("role" + id);
        r.setSuperAdmin(superAdmin);
        List<UserRolePermission> list = new ArrayList<>();
        for (int p : permissions) {
            list.add(permission(p));
        }
        r.setPermissions(list);
        return r;
    }

    private static UserRolePermission permission(int id) {
        UserRolePermission perm = new UserRolePermission();
        perm.setId(id);
        perm.setName("p" + id);
        perm.setSuperAdmin(id == P_SUPER);
        return perm;
    }
}
