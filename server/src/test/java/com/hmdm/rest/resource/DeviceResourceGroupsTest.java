/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.rest.resource;

import com.google.inject.Guice;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.DeviceGroupBulkRequest;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A device edit or bulk regroup into a group the user may not use (another customer's, or one outside a limited user's
 * groups) is answered PERMISSION_DENIED, and a bulk regroup changes no device. DeviceDAO's own check, on PostgreSQL, is
 * DeviceGroupsPgTest.
 */
public class DeviceResourceGroupsTest {

    private static final int USABLE = 5;
    private static final int OTHER_CUSTOMERS = 6;

    private final List<String> calls = new ArrayList<>();

    private DeviceResource resource() {
        DeviceDAO devices = new DeviceDAO(null, null, Guice.createInjector(), null, 3) {
            @Override public Device getDeviceByNumber(String number) { return null; }
            @Override public Device getDeviceById(Integer id) {
                Device d = new Device();
                d.setId(id);
                d.setNumber("d" + id);
                d.setCustomerId(4);
                d.setGroups(new ArrayList<>(Collections.singletonList(new LookupItem(USABLE, "usable"))));
                return d;
            }
            // As DeviceDAO's: refused as a whole when a group named is not usable (DeviceGroupsPgTest checks the DAO)
            @Override public void updateDevicesGroups(Collection<Integer> ids, List<LookupItem> groups, boolean add) {
                for (LookupItem g : groups) {
                    if (g.getId() == OTHER_CUSTOMERS) {
                        throw SecurityException.onCustomerDataAccessViolation(g.getId(), "group");
                    }
                }
                for (Integer id : ids) {
                    calls.add((add ? "add " : "remove ") + id);
                }
            }
            @Override public void updateDevice(Device device) {
                for (LookupItem g : device.getGroups()) {
                    if (g.getId() == OTHER_CUSTOMERS) {
                        throw SecurityException.onCustomerDataAccessViolation(g.getId(), "group");
                    }
                }
                calls.add("update " + device.getId());
            }
        };
        return new DeviceResource(devices, null, null, null, null, null, null);
    }

    @Before
    public void setUp() {
        SecurityContext.init(Fakes.user(9, 4, "edit_devices", "devices"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void anEditIntoAGroupTheUserMayNotUseIsPermissionDenied() {
        Device d = new Device();
        d.setId(1);
        d.setNumber("d1");
        d.setGroups(Arrays.asList(new LookupItem(USABLE, null), new LookupItem(OTHER_CUSTOMERS, null)));

        Response r = resource().updateDevice(d);

        assertEquals(Response.PERMISSION_DENIED().getStatus(), r.getStatus());
        assertEquals(Response.PERMISSION_DENIED().getMessage(), r.getMessage());
        assertTrue(calls.toString(), calls.isEmpty());
    }

    @Test
    public void aBulkRegroupIntoAGroupTheUserMayNotUseChangesNoDevice() {
        DeviceGroupBulkRequest request = new DeviceGroupBulkRequest();
        request.setIds(Arrays.asList(1, 2, 3));
        request.setAction("set");
        request.setGroups(Collections.singletonList(new LookupItem(OTHER_CUSTOMERS, null)));

        Response r = resource().updateDeviceGroupBulk(request);

        assertEquals(Response.PERMISSION_DENIED().getMessage(), r.getMessage());
        assertTrue(calls.toString(), calls.isEmpty());
    }

    @Test
    public void aBulkRegroupIntoTheUsersOwnGroupChangesEveryDevice() {
        DeviceGroupBulkRequest request = new DeviceGroupBulkRequest();
        request.setIds(Arrays.asList(1, 2));
        request.setAction("set");
        request.setGroups(Collections.singletonList(new LookupItem(USABLE, null)));

        Response r = resource().updateDeviceGroupBulk(request);

        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(Arrays.asList("add 1", "add 2"), calls);
    }

    @Test
    public void aBulkRemovalFromAGroupTheUserMayNotUseIsPermissionDenied() {
        DeviceGroupBulkRequest request = new DeviceGroupBulkRequest();
        request.setIds(Arrays.asList(1, 2));
        request.setAction("unset");
        request.setGroups(Collections.singletonList(new LookupItem(OTHER_CUSTOMERS, null)));

        Response r = resource().updateDeviceGroupBulk(request);

        assertEquals(Response.PERMISSION_DENIED().getMessage(), r.getMessage());
        assertTrue(calls.toString(), calls.isEmpty());
    }
}
