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

    private static final int VISIBLE = 1, HIDDEN = 2, OTHER_CUSTOMER_DEVICE = 3;
    private static final int OWN_CONFIG = 5, OWN_CONFIG_2 = 6, OTHER_CONFIG = 7;

    private static Device otherCustomerDevice() {
        Device d = device(OTHER_CUSTOMER_DEVICE);
        d.setCustomerId(2);
        return d;
    }

    private FakeMapper<DeviceMapper> fake;
    private DeviceDAO dao;

    private static Device device(int id) {
        Device d = new Device();
        d.setId(id);
        d.setNumber("DEV" + id);
        d.setCustomerId(1);
        d.setConfigurationId(OWN_CONFIG);
        d.setGroups(new ArrayList<>());
        return d;
    }

    @Before
    public void setUp() {
        fake = new FakeMapper<>(DeviceMapper.class)
                .answer("getDeviceById", a -> a[0] == null || (Integer) a[0] == OTHER_CUSTOMER_DEVICE
                        ? (a[0] == null ? null : otherCustomerDevice()) : device((Integer) a[0]))
                .answer("getDeviceByNumber", a -> a[0] == null ? null : device(Integer.parseInt(((String) a[0]).substring(3))))
                .answer("isDeviceAccessibleToUser", a -> ((Integer) a[0]) == VISIBLE)
                .answer("isCustomerConfiguration", a -> ((Integer) a[1]) == 1
                        && (((Integer) a[0]) == OWN_CONFIG || ((Integer) a[0]) == OWN_CONFIG_2));
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

    private boolean noDeviceWrites() {
        return fake.callsStartingWith("remove", "update", "delete", "insert").isEmpty();
    }

    @Test
    public void bulkActionsAreRefusedAsAWholeWhenAnyDeviceIsOutOfReach() {
        DeviceResource resource = new DeviceResource(dao, null, null, null, null, null, null);
        for (int outOfReach : new int[]{HIDDEN, OTHER_CUSTOMER_DEVICE}) {
            Device bulkDelete = new Device();
            bulkDelete.setIds(Fakes.list(VISIBLE, outOfReach));
            assertNotEquals(Response.ResponseStatus.OK, resource.removeBulkDevices(bulkDelete).getStatus());

            com.hmdm.rest.json.DeviceGroupBulkRequest regroup = new com.hmdm.rest.json.DeviceGroupBulkRequest();
            regroup.setIds(Fakes.list(VISIBLE, outOfReach));
            regroup.setAction("set");
            regroup.setGroups(new ArrayList<>());
            assertNotEquals(Response.ResponseStatus.OK, resource.updateDeviceGroupBulk(regroup).getStatus());

            Device reassign = new Device();
            reassign.setIds(Fakes.list(VISIBLE, outOfReach));
            reassign.setConfigurationId(5);
            assertNotEquals(Response.ResponseStatus.OK, resource.updateDevice(reassign).getStatus());

            assertTrue("nothing may be written: " + fake.calls, noDeviceWrites());
        }
    }

    @Test
    public void bulkDeleteOfReachableDevicesStillWorks() {
        DeviceResource resource = new DeviceResource(dao, null, null, null, null, null, null);
        Device bulkDelete = new Device();
        bulkDelete.setIds(Fakes.list(VISIBLE));
        assertEquals(Response.ResponseStatus.OK, resource.removeBulkDevices(bulkDelete).getStatus());
        assertEquals(1, fake.callsTo("removeDevice").size());
    }

    private static void expectDenied(String name, Runnable op) {
        try {
            op.run();
            fail(name + " must be rejected");
        } catch (SecurityException expected) {
            // ok
        }
    }

    private static Device withConfiguration(int id, int configurationId) {
        Device d = device(id);
        d.setConfigurationId(configurationId);
        return d;
    }

    @Test
    public void aDeviceCannotBePointedAtAnotherCustomersConfiguration() {
        expectDenied("configuration", () -> dao.updateDeviceConfiguration(VISIBLE, OTHER_CONFIG));
        expectDenied("update", () -> dao.updateDevice(withConfiguration(VISIBLE, OTHER_CONFIG)));
        Device created = withConfiguration(0, OTHER_CONFIG);
        created.setId(null);
        expectDenied("insert", () -> dao.insertDevice(created));
        assertTrue(fake.calls.toString(), noDeviceWrites());
        String where = Fakes.whereOf(Fakes.sqlOf(DeviceMapper.class, "updateDeviceConfiguration"));
        assertTrue(where, where.contains("configurations.customerid"));
    }

    @Test
    public void aUserLimitedToSomeConfigurationsCannotMoveADeviceIntoAnotherOne() {
        com.hmdm.persistence.domain.User u = Fakes.limitedToConfigurations(Fakes.user(9, 1, "edit_devices"), OWN_CONFIG);
        u.setAllDevicesAvailable(false);
        SecurityContext.release();
        SecurityContext.init(u);
        expectDenied("configuration", () -> dao.updateDeviceConfiguration(VISIBLE, OWN_CONFIG_2));
        assertTrue(fake.calls.toString(), noDeviceWrites());
        dao.updateDeviceConfiguration(VISIBLE, OWN_CONFIG);
        assertEquals(1, fake.callsTo("updateDeviceConfiguration").size());
    }

    @Test
    public void bulkConfigurationChangeToAnotherCustomersConfigurationWritesNothing() {
        DeviceResource resource = new DeviceResource(dao, null, null, null, null, null, null);
        Device reassign = new Device();
        reassign.setIds(Fakes.list(VISIBLE));
        reassign.setConfigurationId(OTHER_CONFIG);
        assertNotEquals(Response.ResponseStatus.OK, resource.updateDevice(reassign).getStatus());
        assertTrue(fake.calls.toString(), noDeviceWrites());
    }

    @Test
    public void notifyingAHiddenDeviceIsRefused() {
        DeviceResource resource = new DeviceResource(dao, null, null, null, null, null, null);
        assertEquals(Response.PERMISSION_DENIED().getMessage(), resource.notifyDevicesOnUpdate(HIDDEN).getMessage());
    }

    @Test
    public void aSuperAdminIsNotLimitedByDeviceGroups() {
        com.hmdm.persistence.domain.User admin = Fakes.superAdmin(1, 1);
        admin.setAllDevicesAvailable(false);
        SecurityContext.release();
        SecurityContext.init(admin);
        dao.updateDeviceDescription(HIDDEN, "x");
        assertEquals(1, fake.callsTo("updateDeviceDescription").size());
    }

    @Test
    public void deviceDetailsByNumberAreNotServedForAHiddenDevice() {
        DeviceResource resource = new DeviceResource(dao, null, null, null, null, null, null);
        assertNotEquals(Response.ResponseStatus.OK, resource.getDevice("DEV" + HIDDEN).getStatus());
        assertEquals(Response.ResponseStatus.OK, resource.getDevice("DEV" + VISIBLE).getStatus());
    }
}
