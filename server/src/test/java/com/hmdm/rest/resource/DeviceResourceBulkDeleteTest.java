/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.google.inject.Guice;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * What bulk delete answers (DeviceDAO.removeDevicesByIds does the work; DeviceHistoryOnDeleteTest runs it on
 * PostgreSQL): OK only when every device went, PERMISSION_DENIED when the check refused one (nothing deleted), and an
 * error listing what was and wasn't deleted when some deletes failed.
 */
public class DeviceResourceBulkDeleteTest {

    private DeviceDAO.BulkDeleteResult result;
    private boolean refuse;

    private DeviceResource resource() {
        DeviceDAO devices = new DeviceDAO(null, null, Guice.createInjector(), null, 3) {
            @Override public boolean areAccessibleToUser(Collection<Integer> ids) { return true; }
            @Override public BulkDeleteResult removeDevicesByIds(Collection<Integer> ids) {
                if (refuse) {
                    Device denied = new Device();
                    denied.setId(2);
                    throw SecurityException.onDeviceAccessViolation(denied);
                }
                return result;
            }
        };
        return new DeviceResource(devices, null, null, null, null, null, null);
    }

    private static Device ids(Integer... ids) {
        Device d = new Device();
        d.setIds(Arrays.asList(ids));
        return d;
    }

    @Before
    public void setUp() {
        SecurityContext.init(Fakes.user(9, 4, "edit_devices", "devices"));
        result = new DeviceDAO.BulkDeleteResult();
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void everyDeviceDeletedIsOk() {
        result.deleted.addAll(Arrays.asList(1, 2));
        assertEquals(Response.ResponseStatus.OK, resource().removeBulkDevices(ids(1, 2)).getStatus());
    }

    @Test
    public void aRefusedCheckIsPermissionDenied() {
        refuse = true;
        Response r = resource().removeBulkDevices(ids(1, 2));
        assertEquals(Response.PERMISSION_DENIED().getStatus(), r.getStatus());
        assertEquals(Response.PERMISSION_DENIED().getMessage(), r.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void somethingNotDeletedIsAnErrorThatSaysWhat() {
        result.deleted.add(1);
        result.notDeleted.add(2);
        Response r = resource().removeBulkDevices(ids(1, 2));
        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals("error.device.delete.partial", r.getMessage());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(Arrays.asList(1), data.get("deleted"));
        assertEquals(Arrays.asList(2), data.get("notDeleted"));
    }
}
