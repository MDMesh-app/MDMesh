package com.hmdm.testsupport;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.ApplicationSetting;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.ConfigurationFile;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.Settings;
import com.hmdm.persistence.mapper.CommonMapper;
import com.hmdm.persistence.mapper.ConfigurationMapper;
import com.hmdm.persistence.mapper.CustomerMapper;
import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.testsupport.Fakes.FakeMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An install the Headwind launcher's device API talks to, without a database: a real {@link UnsecureDAO} over
 * in-memory mappers, holding launcher devices and agent-enrolled ones (devices with a check-in secret). A launcher
 * configuration no agent device uses, and an agent configuration that one does. Every write the routes can make
 * through the DAO is recorded in {@link #writes} (mapper writes in {@link #devices}' calls).
 */
public final class LegacyDeviceWorld {

    public static final int CUSTOMER = 1;
    public static final int LAUNCHER_CONFIG = 10;
    public static final int AGENT_CONFIG = 20;
    public static final String LAUNCHER_QR_KEY = "launcher-key";
    public static final String AGENT_QR_KEY = "agent-key";

    public final Map<String, Device> byNumber = new HashMap<>();
    public final Map<String, Device> byOldNumber = new HashMap<>();
    public final Map<String, Device> byImeiOrSerial = new HashMap<>();
    public final Map<Integer, Device> byId = new HashMap<>();
    /** Ids of the devices with an agent check-in secret. */
    public final Set<Integer> agentDeviceIds = new HashSet<>();
    public final Settings settings = new Settings();
    /** The install has several customers. */
    public boolean multiTenant = false;
    public final List<String> writes = new ArrayList<>();
    public final FakeMapper<DeviceMapper> devices;
    public final UnsecureDAO dao;

    private int nextId = 100;

    public LegacyDeviceWorld() {
        settings.setCustomerId(CUSTOMER);
        settings.setCreateNewDevices(true);
        settings.setNewDeviceConfigurationId(LAUNCHER_CONFIG);

        devices = new FakeMapper<>(DeviceMapper.class)
                .answer("getDeviceByNumber", a -> byNumber.get((String) a[0]))
                .answer("getDeviceByOldNumber", a -> byOldNumber.get((String) a[0]))
                .answer("getDeviceByImeiOrSerial", a -> byImeiOrSerial.get((String) a[0]))
                .answer("getDeviceById", a -> byId.get((Integer) a[0]))
                .answer("isAgentDevice", a -> agentDeviceIds.contains((Integer) a[0]))
                .answer("isAgentConfiguration", a -> byId.values().stream().anyMatch(d ->
                        agentDeviceIds.contains(d.getId()) && a[0].equals(d.getConfigurationId())))
                .answer("isCustomerConfiguration", a -> true)
                .answer("insertDevice", a -> {
                    Device d = (Device) a[0];
                    d.setId(nextId++);
                    put(d);
                    return 1;
                });
        FakeMapper<CommonMapper> settingsMapper = new FakeMapper<>(CommonMapper.class)
                .answer("getSettings", a -> settings);
        FakeMapper<ConfigurationMapper> configurations = new FakeMapper<>(ConfigurationMapper.class)
                .answer("getConfigurationByQRCodeKey", a -> LAUNCHER_QR_KEY.equals(a[0]) ? configuration(LAUNCHER_CONFIG)
                        : AGENT_QR_KEY.equals(a[0]) ? configuration(AGENT_CONFIG) : null)
                .answer("getConfigurationById", a -> configuration((Integer) a[0]));
        FakeMapper<CustomerMapper> customers = new FakeMapper<>(CustomerMapper.class)
                .answer("isMultiTenant", a -> multiTenant);

        dao = new UnsecureDAO(devices.mapper, null, configurations.mapper, settingsMapper.mapper, null, null, null, null,
                null, null, null, null, customers.mapper, null, "files", 2, "launcher") {
            // What the configuration answer reads besides the devices: a plain configuration, nothing assigned.
            @Override public List<Application> getPlainConfigurationApplications(Integer customerId, Integer id) {
                return new ArrayList<>();
            }
            @Override public Configuration getConfigurationByIdWithAppSettings(Integer id) {
                Configuration c = configuration(id);
                c.setApplicationSettings(new ArrayList<>());
                return c;
            }
            @Override public List<ApplicationSetting> getDeviceAppSettings(int deviceId) {
                return new ArrayList<>();
            }
            @Override public List<ConfigurationFile> getConfigurationFiles(Device device) {
                return new ArrayList<>();
            }
            @Override public void saveDeviceApplicationSettings(Device device, List<ApplicationSetting> settings) {
                writes.add("applicationSettings " + device.getNumber());
            }
            @Override public Map<String, Integer> buildPackageIdMapping(Integer customerId,
                                                                        java.util.Collection<String> packages) {
                return new HashMap<>();
            }
        };
    }

    /** A configuration as the database holds it: no kiosk exit PIN set. */
    public static Configuration configuration(Integer id) {
        if (id == null) return null;
        Configuration c = new Configuration();
        c.setId(id);
        c.setCustomerId(CUSTOMER);
        c.setName("config " + id);
        c.setQrCodeKey(id == AGENT_CONFIG ? AGENT_QR_KEY : LAUNCHER_QR_KEY);
        return c;
    }

    /** A launcher device: no check-in secret. */
    public Device launcher(String number, int configurationId) {
        Device d = device(number, configurationId);
        put(d);
        return d;
    }

    /** An agent-enrolled device in {@link #AGENT_CONFIG}, with an IMEI, a serial and a number from before a migration. */
    public Device agent(String number) {
        Device d = device(number, AGENT_CONFIG);
        d.setOldNumber(number + "-old");
        d.setImei(number + "-imei");
        put(d);
        byImeiOrSerial.put(number + "-serial", d);
        agentDeviceIds.add(d.getId());
        return d;
    }

    /** Every write the routes made: through the DAO, and to the device mapper. */
    public List<String> allWrites() {
        List<String> all = new ArrayList<>(writes);
        devices.callsStartingWith("insert", "update", "delete", "clear", "save")
                .forEach(c -> all.add(c.toString()));
        return all;
    }

    private Device device(String number, int configurationId) {
        Device d = new Device();
        d.setId(nextId++);
        d.setNumber(number);
        d.setCustomerId(CUSTOMER);
        d.setConfigurationId(configurationId);
        d.setLastUpdate(0L);
        d.setInfo("{}");
        return d;
    }

    private void put(Device d) {
        byNumber.put(d.getNumber(), d);
        byId.put(d.getId(), d);
        if (d.getOldNumber() != null) byOldNumber.put(d.getOldNumber(), d);
        if (d.getImei() != null) byImeiOrSerial.put(d.getImei(), d);
    }
}
