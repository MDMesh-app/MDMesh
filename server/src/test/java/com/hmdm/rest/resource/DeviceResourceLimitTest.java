/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.google.inject.Guice;
import com.hmdm.persistence.CommonDAO;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.Settings;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * A device the console adds goes in through DeviceDAO.insertDeviceWithinLimit, which counts it under the per-customer
 * lock every device-adding path shares (DeviceLimitPathsTest races it on PostgreSQL), never a plain insert.
 */
public class DeviceResourceLimitTest {

    private final List<String> calls = new ArrayList<>();
    private boolean room = true;
    private boolean singleCustomer = false;

    private DeviceResource resource() {
        DeviceDAO devices = new DeviceDAO(null, null, Guice.createInjector(), null, 3) {
            @Override public Device getDeviceByNumber(String number) { return null; }
            @Override public Device getDeviceById(Integer id) { return null; }
            @Override public boolean insertDeviceWithinLimit(Device device, int deviceLimit) {
                calls.add("withinLimit " + device.getNumber() + " " + deviceLimit);
                return room;
            }
            @Override public void insertDevice(Device device) { calls.add("plain insert " + device.getNumber()); }
        };
        CommonDAO common = new CommonDAO(null, null, null) {
            @Override public void loadCustomerSettings(Settings settings) { settings.setDeviceLimit(3); }
        };
        UnsecureDAO unsecure = new UnsecureDAO(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, "files", 2, "launcher") {
            @Override public boolean isSingleCustomer() { return singleCustomer; }
        };
        return new DeviceResource(devices, null, null, null, common, unsecure, null);
    }

    private static Device newDevice() {
        Device d = new Device();
        d.setNumber("new-1");
        return d;
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
    public void a_new_device_is_added_within_the_customers_limit() {
        Response r = resource().updateDevice(newDevice());

        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(java.util.Collections.singletonList("withinLimit new-1 3"), calls);
    }

    @Test
    public void a_full_customer_gets_an_error_and_nothing_is_inserted() {
        room = false;

        Response r = resource().updateDevice(newDevice());

        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals(java.util.Collections.singletonList("withinLimit new-1 3"), calls);
    }

    @Test
    public void a_single_customer_install_has_no_limit() {
        singleCustomer = true;

        resource().updateDevice(newDevice());

        assertEquals(java.util.Collections.singletonList("withinLimit new-1 0"), calls);
    }
}
