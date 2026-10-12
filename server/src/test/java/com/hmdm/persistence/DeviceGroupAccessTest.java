package com.hmdm.persistence;

import com.google.inject.Guice;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.DeviceResource;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A user limited to some device groups cannot read or change a device outside them through the device DAO. */
public class DeviceGroupAccessTest {

    private static final int VISIBLE = 1, HIDDEN = 2;

    private FakeMapper<DeviceMapper> fake;
    private DeviceDAO dao;

    private static Device device(int id) {
        Device d = new Device();
        d.setId(id);
        d.setNumber("DEV" + id);
        d.setCustomerId(1);
        d.setGroups(new ArrayList<>());
        return d;
    }

    @Before
    public void setUp() {
        fake = new FakeMapper<>(DeviceMapper.class)
                .answer("getDeviceById", a -> device((Integer) a[0]))
                .answer("getDeviceByNumber", a -> device(Integer.parseInt(((String) a[0]).substring(3))))
                .answer("isDeviceAccessibleToUser", a -> ((Integer) a[0]) == VISIBLE);
        dao = new DeviceDAO(fake.mapper, null, Guice.createInjector(), null, 3);
        com.hmdm.persistence.domain.User u = Fakes.user(9, 1, "edit_devices", "devices", "edit_device_desc");
        u.setAllDevicesAvailable(false);
        SecurityContext.init(u);
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void everyDeviceWriteAndDetailReadRejectsAHiddenDevice() {
        Map<String, Runnable> ops = new LinkedHashMap<>();
        ops.put("remove", () -> dao.removeDeviceById(HIDDEN));
        ops.put("configuration", () -> dao.updateDeviceConfiguration(HIDDEN, 5));
        ops.put("update", () -> dao.updateDevice(device(HIDDEN)));
        ops.put("description", () -> dao.updateDeviceDescription(HIDDEN, "x"));
        ops.put("saveAppSettings", () -> dao.saveDeviceApplicationSettings(HIDDEN, new ArrayList<>()));
        ops.put("appSettings", () -> dao.getDeviceApplicationSettings(HIDDEN));
        ops.put("installedApps", () -> dao.getDeviceInstalledApplications(HIDDEN));
        ops.forEach((name, op) -> {
            try {
                op.run();
                fail(name + " must be rejected");
            } catch (SecurityException expected) {
                // ok
            }
        });
        assertTrue(fake.calls.toString(), fake.callsStartingWith(
                "remove", "update", "delete", "insert", "getDeviceInstalledApplications").isEmpty());
    }

    @Test
    public void aVisibleDeviceCanStillBeChanged() {
        dao.updateDeviceDescription(VISIBLE, "x");
        dao.removeDeviceById(VISIBLE);
        assertEquals(1, fake.callsTo("updateDeviceDescription").size());
        assertEquals(1, fake.callsTo("removeDevice").size());
    }

    @Test
    public void deviceDetailsByNumberAreNotServedForAHiddenDevice() {
        DeviceResource resource = new DeviceResource(dao, null, null, null, null, null, null);
        assertNotEquals(Response.ResponseStatus.OK, resource.getDevice("DEV" + HIDDEN).getStatus());
        assertEquals(Response.ResponseStatus.OK, resource.getDevice("DEV" + VISIBLE).getStatus());
    }
}
