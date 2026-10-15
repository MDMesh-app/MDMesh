package com.hmdm.persistence;

import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Settings;
import com.hmdm.persistence.mapper.CommonMapper;
import com.hmdm.persistence.mapper.ConfigurationMapper;
import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** New devices only ever land in a configuration of their own customer. */
public class NewDeviceConfigurationTest {

    private static final int CUSTOMER = 1, OWN_CONFIG = 5, OTHER_CONFIG = 7, OWN_GROUP = 3, OTHER_GROUP = 8;

    private static com.hmdm.persistence.domain.Group group(int id) {
        com.hmdm.persistence.domain.Group g = new com.hmdm.persistence.domain.Group();
        g.setId(id);
        g.setCustomerId(id == OWN_GROUP ? CUSTOMER : 2);
        return g;
    }

    private static FakeMapper<DeviceMapper> devicesWithGroups() {
        return new FakeMapper<>(DeviceMapper.class)
                .answer("getGroupById", a -> group((Integer) a[0]))
                .answer("isCustomerGroup", a -> ((Integer) a[0]) == OWN_GROUP && ((Integer) a[1]) == CUSTOMER)
                .answer("isCustomerConfiguration", a -> ((Integer) a[0]) == OWN_CONFIG && ((Integer) a[1]) == CUSTOMER);
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static Configuration configuration(int id) {
        Configuration c = new Configuration();
        c.setId(id);
        c.setCustomerId(id == OWN_CONFIG ? CUSTOMER : 2);
        return c;
    }

    private static UnsecureDAO unsecureDAO(FakeMapper<DeviceMapper> devices, Integer defaultConfigurationId) {
        return unsecureDAO(devices, defaultConfigurationId, null);
    }

    private static UnsecureDAO unsecureDAO(FakeMapper<DeviceMapper> devices, Integer defaultConfigurationId,
                                           Integer defaultGroupId) {
        FakeMapper<CommonMapper> settings = new FakeMapper<>(CommonMapper.class).answer("getSettings", a -> {
            Settings s = new Settings();
            s.setCreateNewDevices(true);
            s.setNewDeviceConfigurationId(defaultConfigurationId);
            s.setNewDeviceGroupId(defaultGroupId);
            return s;
        });
        FakeMapper<ConfigurationMapper> configurations = new FakeMapper<>(ConfigurationMapper.class)
                .answer("getConfigurationById", a -> configuration((Integer) a[0]));
        return new UnsecureDAO(devices.mapper, null, configurations.mapper, settings.mapper, null, null, null, null,
                null, null, null, null, null, null, "files", 2, "launcher");
    }

    @Test
    public void tokenEnrollmentRefusesADefaultConfigurationOfAnotherCustomer() {
        FakeMapper<DeviceMapper> devices = devicesWithGroups();
        assertNull(unsecureDAO(devices, OTHER_CONFIG).createNewDeviceForToken("new-device", CUSTOMER, null));
        assertTrue(devices.calls.toString(), devices.callsTo("insertDevice").isEmpty());
    }

    @Test
    public void tokenEnrollmentRefusesABoundConfigurationOfAnotherCustomer() {
        FakeMapper<DeviceMapper> devices = devicesWithGroups();
        assertNull(unsecureDAO(devices, OWN_CONFIG).createNewDeviceForToken("new-device", CUSTOMER, OTHER_CONFIG));
        assertTrue(devices.calls.toString(), devices.callsTo("insertDevice").isEmpty());
    }

    @Test
    public void tokenEnrollmentStillUsesTheOwnDefaultConfiguration() {
        FakeMapper<DeviceMapper> devices = devicesWithGroups();
        unsecureDAO(devices, OWN_CONFIG).createNewDeviceForToken("new-device", CUSTOMER, null);
        assertTrue(devices.calls.toString(), devices.callsTo("insertDevice").size() == 1);
    }

    @Test
    public void tokenEnrollmentDoesNotPutTheDeviceIntoAnotherCustomersGroup() {
        FakeMapper<DeviceMapper> devices = devicesWithGroups();
        unsecureDAO(devices, OWN_CONFIG, OTHER_GROUP).createNewDeviceForToken("new-device", CUSTOMER, null);
        assertTrue(devices.calls.toString(), devices.callsTo("insertDevice").size() == 1);
        assertTrue(devices.calls.toString(), devices.callsTo("insertDeviceGroups").isEmpty());
    }

    @Test
    public void tokenEnrollmentStillUsesTheOwnDefaultGroup() {
        FakeMapper<DeviceMapper> devices = devicesWithGroups();
        unsecureDAO(devices, OWN_CONFIG, OWN_GROUP).createNewDeviceForToken("new-device", CUSTOMER, null);
        assertTrue(devices.calls.toString(), devices.callsTo("insertDeviceGroups").size() == 1);
    }

    @Test
    public void theDefaultGroupForNewDevicesMustBeOwn() {
        FakeMapper<CommonMapper> common = new FakeMapper<>(CommonMapper.class);
        CommonDAO dao = new CommonDAO(common.mapper, null, devicesWithGroups().mapper);
        SecurityContext.init(Fakes.user(5, CUSTOMER, "settings"));

        Settings s = new Settings();
        s.setNewDeviceConfigurationId(OWN_CONFIG);
        s.setNewDeviceGroupId(OTHER_GROUP);
        try {
            dao.saveMiscSettings(s);
            fail("expected a SecurityException");
        } catch (SecurityException expected) {
            // ok
        }
        assertTrue(common.calls.toString(), common.callsTo("saveMiscSettings").isEmpty());

        s.setNewDeviceGroupId(OWN_GROUP);
        dao.saveMiscSettings(s);
        assertTrue(common.calls.toString(), common.callsTo("saveMiscSettings").size() == 1);
    }

    @Test
    public void theDefaultConfigurationForNewDevicesMustBeOwn() {
        FakeMapper<CommonMapper> common = new FakeMapper<>(CommonMapper.class);
        FakeMapper<DeviceMapper> devices = new FakeMapper<>(DeviceMapper.class)
                .answer("isCustomerConfiguration", a -> ((Integer) a[0]) == OWN_CONFIG && ((Integer) a[1]) == CUSTOMER);
        CommonDAO dao = new CommonDAO(common.mapper, null, devices.mapper);
        SecurityContext.init(Fakes.user(5, CUSTOMER, "settings"));

        Settings s = new Settings();
        s.setNewDeviceConfigurationId(OTHER_CONFIG);
        try {
            dao.saveMiscSettings(s);
            fail("expected a SecurityException");
        } catch (SecurityException expected) {
            // ok
        }
        assertTrue(common.calls.toString(), common.callsTo("saveMiscSettings").isEmpty());

        s.setNewDeviceConfigurationId(OWN_CONFIG);
        dao.saveMiscSettings(s);
        assertTrue(common.calls.toString(), common.callsTo("saveMiscSettings").size() == 1);
    }
}
