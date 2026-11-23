package com.hmdm.rest.resource;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.UserRoleDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.persistence.domain.UserRolePermission;
import com.hmdm.persistence.mapper.CustomerMapper;
import com.hmdm.persistence.mapper.UserRoleMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

/** Exercise role administration through its resource and real DAO, including direct forged API requests. */
public class UserRoleResourceTest {
    private FakeMapper<UserRoleMapper> roles;
    private UserRoleResource resource;
    private boolean multiTenant;
    private int assignedUsers;
    private UserRole existing;

    @Before
    public void setUp() {
        roles = new FakeMapper<>(UserRoleMapper.class);
        roles.answer("findById", a -> existing);
        roles.answer("getPermissionsList", a -> Collections.singletonList(permission(10)));
        roles.answer("countUsers", a -> assignedUsers);
        FakeMapper<CustomerMapper> customers = new FakeMapper<>(CustomerMapper.class);
        customers.answer("isMultiTenant", a -> multiTenant);
        UnsecureDAO unsecure = new UnsecureDAO(null, null, null, null, null, null, null, null, null, null,
                null, null, customers.mapper, null, ".", 2, "com.hmdm.launcher");
        resource = new UserRoleResource(new UserRoleDAO(unsecure, new UserDAO(null, 2), roles.mapper, 2));
        existing = role(7, false);
        signIn(2, false);
    }

    @After public void tearDown() { SecurityContext.release(); }

    @Test public void ordinaryUserCannotReadOrWriteRoles() {
        signIn(3, false);
        assertDenied(resource.getAllRoles());
        assertDenied(resource.getPermissions());
        assertDenied(resource.updateRole(role(null, false)));
        assertDenied(resource.removeUserRole(7));
        assertTrue(roles.calls.isEmpty());
    }

    @Test public void tenantAdminCannotChangeGlobalRoles() {
        multiTenant = true;
        assertDenied(resource.updateRole(role(null, false)));
        assertTrue(roles.calls.isEmpty());
    }

    @Test public void superAdminCanManageRolesOnMultiTenantInstall() {
        multiTenant = true;
        signIn(1, true);
        assertEquals(Response.ResponseStatus.OK, resource.updateRole(role(null, false)).getStatus());
        assertEquals(1, roles.callsTo("insert").size());
    }

    @Test public void protectedRolePermissionsCannotBeChangedByForgingItsId() {
        for (UserRole protectedRole : Arrays.asList(role(1, true), role(2, false))) {
            existing = protectedRole;
            assertError("error.role.protected", resource.updateRole(role(protectedRole.getId(), false)));
            assertError("error.role.protected", resource.removeUserRole(protectedRole.getId()));
        }
        assertNoWrites();
    }

    @Test public void unknownRoleCannotBeUpdated() {
        existing = null;
        assertError("error.role.protected", resource.updateRole(role(999, false)));
        assertNoWrites();
    }

    @Test public void unknownAndDuplicatePermissionIdsAreRefusedBeforeAnyWrite() {
        UserRole request = role(7, false);
        request.setPermissions(Collections.singletonList(permission(999)));
        assertError("error.role.permission", resource.updateRole(request));
        request.setPermissions(Arrays.asList(permission(10), permission(10)));
        assertError("error.role.permission", resource.updateRole(request));
        assertNoWrites();
    }

    @Test public void cannotCreateSuperAdminRole() {
        assertError("error.role.protected", resource.updateRole(role(null, true)));
        assertNoWrites();
    }

    @Test public void duplicateAndEmptyNamesAreRefused() {
        roles.answer("findByName", a -> existing);
        assertError("error.duplicate.role", resource.updateRole(role(null, false)));
        UserRole blank = role(null, false);
        blank.setName("   ");
        assertError("error.role.name", resource.updateRole(blank));
        assertNoWrites();
    }

    @Test public void assignedRoleCannotBeDeleted() {
        assignedUsers = 1;
        assertError("error.role.in.use", resource.removeUserRole(7));
        assertNoWrites();
    }

    @Test public void editableRoleCanBeCreatedUpdatedAndDeleted() {
        assertEquals(Response.ResponseStatus.OK, resource.updateRole(role(null, false)).getStatus());
        assertEquals(Response.ResponseStatus.OK, resource.updateRole(role(7, false)).getStatus());
        assertEquals(Response.ResponseStatus.OK, resource.removeUserRole(7).getStatus());
        assertEquals(1, roles.callsTo("insert").size());
        assertEquals(1, roles.callsTo("update").size());
        assertEquals(1, roles.callsTo("delete").size());
        assertEquals(2, roles.callsTo("insertPermissions").size());
    }

    private void assertNoWrites() { assertTrue(roles.callsStartingWith("insert", "update", "delete").isEmpty()); }
    private static void assertDenied(Response r) { assertEquals(Response.PERMISSION_DENIED().getStatus(), r.getStatus()); }
    private static void assertError(String message, Response r) { assertEquals(Response.ResponseStatus.ERROR, r.getStatus()); assertEquals(message, r.getMessage()); }
    private static void signIn(int roleId, boolean superAdmin) {
        SecurityContext.release();
        User user = new User(); user.setId(1); user.setLogin("admin"); user.setCustomerId(1);
        user.setUserRole(role(roleId, superAdmin)); SecurityContext.init(user);
    }
    private static UserRole role(Integer id, boolean superAdmin) {
        UserRole r = new UserRole(); r.setId(id); r.setName("Operator"); r.setSuperAdmin(superAdmin);
        r.setPermissions(Collections.singletonList(permission(10))); return r;
    }
    private static UserRolePermission permission(int id) { UserRolePermission p = new UserRolePermission(); p.setId(id); p.setName("applications"); return p; }
}
