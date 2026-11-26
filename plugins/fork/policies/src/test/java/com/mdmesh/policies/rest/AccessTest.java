package com.mdmesh.policies.rest;

import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.rest.json.Response;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class AccessTest {
    private static User user(boolean superAdmin, boolean allDevices) {
        UserRole role = new UserRole();
        role.setSuperAdmin(superAdmin);
        User user = new User();
        user.setLogin("someone");
        user.setUserRole(role);
        user.setAllDevicesAvailable(allDevices);
        return user;
    }

    @Test
    public void aUserWhoMayManageAllPhonesMayChangeFleetWideSettings() {
        assertNull(Access.fleetWide(user(false, true)));
    }

    @Test
    public void aSuperAdminMayChangeThemEvenWhenLimitedToSomeGroups() {
        assertNull(Access.fleetWide(user(true, false)));
    }

    @Test
    public void aUserLimitedToSomeDeviceGroupsIsRefusedWithTheReason() {
        Response refused = Access.fleetWide(user(false, false));
        assertNotNull(refused);
        assertEquals(Response.ResponseStatus.ERROR, refused.getStatus());
        assertEquals("This acts on every phone of a policy, so only a user who may manage all phones can change it.",
                refused.getMessage());
    }
}
