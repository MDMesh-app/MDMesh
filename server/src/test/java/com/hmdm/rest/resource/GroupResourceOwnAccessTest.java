/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.GroupDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.domain.Group;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * PUT /private/groups by a user limited to some device groups: the new group is added to their access as one row. The
 * session's copy of the user is never written back, so an access change made meanwhile (a demotion) is not undone.
 */
public class GroupResourceOwnAccessTest {

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void aLimitedUserGetsTheNewGroupAndTheirStaleSessionIsNotWrittenBack() {
        User session = Fakes.user(10, 1, "settings");
        session.setAllDevicesAvailable(false);
        session.setGroups(Fakes.list(new LookupItem(5, "five")));
        SecurityContext.init(session);

        Fakes.FakeMapper<DeviceMapper> devices = new Fakes.FakeMapper<>(DeviceMapper.class)
                .answer("insertGroup", a -> {
                    ((Group) a[0]).setId(42);
                    return null;
                });
        Fakes.FakeMapper<UserMapper> users = new Fakes.FakeMapper<>(UserMapper.class);
        GroupResource resource = new GroupResource(new GroupDAO(devices.mapper), new UserDAO(users.mapper, 2));

        Group group = new Group();
        group.setName("New");
        assertEquals(Response.ResponseStatus.OK, resource.updateGroup(group).getStatus());

        assertEquals(1, users.callsTo("insertUserDeviceGroupsAccess").size());
        assertEquals(10, users.callsTo("insertUserDeviceGroupsAccess").get(0).args[0]);
        assertEquals(Collections.singletonList(42), users.callsTo("insertUserDeviceGroupsAccess").get(0).args[1]);
        // Nothing else of the user: no main details, no access rows replaced
        assertTrue(users.calls.toString(), users.callsStartingWith("update", "delete").isEmpty());
    }

    @Test
    public void aUserReachingAllDevicesGetsNoAccessRow() {
        SecurityContext.init(Fakes.user(10, 1, "settings"));
        Fakes.FakeMapper<DeviceMapper> devices = new Fakes.FakeMapper<>(DeviceMapper.class);
        Fakes.FakeMapper<UserMapper> users = new Fakes.FakeMapper<>(UserMapper.class);
        GroupResource resource = new GroupResource(new GroupDAO(devices.mapper), new UserDAO(users.mapper, 2));

        Group group = new Group();
        group.setName("New");
        assertEquals(Response.ResponseStatus.OK, resource.updateGroup(group).getStatus());
        assertTrue(users.calls.toString(), users.calls.isEmpty());
    }
}
