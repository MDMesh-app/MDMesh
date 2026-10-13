package com.hmdm.notification;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import com.hmdm.notification.guice.module.NotificationMqttConfigModule;
import com.hmdm.notification.persistence.domain.PushMessage;
import com.hmdm.persistence.ConfigurationDAO;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.filter.LegacyDeviceApiFilter;
import org.junit.Test;

import javax.servlet.ServletContext;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Only the Headwind launcher reads the push queue ({@code pushMessages}/{@code pendingPushes}), so {@link PushService}
 * writes nothing, and looks nothing up, while the legacy device API is off; with it on, every message still goes to
 * both senders. The senders and DAOs are hand-made fakes.
 */
public class PushServiceLegacySwitchTest {

    private final List<String> sent = new ArrayList<>();
    private final List<String> lookups = new ArrayList<>();

    private PushSender sender(String name) {
        return new PushSender() {
            @Override public void init() { }
            @Override public int send(PushMessage message) {
                sent.add(name + ":" + message.getDeviceId() + ":" + message.getMessageType());
                return 1;
            }
        };
    }

    private PushService service(boolean legacy) {
        ConfigurationDAO configurations = new ConfigurationDAO(null, null, null, null, "https://mdm.example", "/tmp", null) {
            @Override public Configuration getConfigurationById(Integer id) {
                lookups.add("configuration " + id);
                Configuration c = new Configuration();
                c.setId(id);
                return c;
            }
        };
        DeviceDAO devices = new DeviceDAO(null, null, Guice.createInjector(), null, 3) {
            @Override public List<Device> getDeviceIdsByConfigurationId(int configurationId) {
                lookups.add("devices of " + configurationId);
                Device d = new Device();
                d.setId(7);
                return Collections.singletonList(d);
            }
            @Override public Device getDeviceById(Integer deviceId) {
                lookups.add("device " + deviceId);
                Device d = new Device();
                d.setId(deviceId);
                return d;
            }
        };
        return new PushService(sender("mqtt"), sender("polling"), configurations, devices, legacy);
    }

    @Test
    public void nothingIsQueuedOrLookedUpWhileTheLegacyDeviceApiIsOff() {
        PushService push = service(false);
        assertFalse(push.isEnabled());
        push.notifyDevicesOnUpdate(1);
        push.notifyDeviceOnSettingUpdate(7);
        push.notifyDeviceOnApplicationSettingUpdate(7);
        push.sendSimpleMessage(7, PushMessage.TYPE_CONFIG_UPDATED);
        PushMessage m = new PushMessage();
        m.setDeviceId(7);
        m.setMessageType(PushMessage.TYPE_CONFIG_UPDATED);
        assertEquals(0, push.send(m));
        assertTrue(sent.toString(), sent.isEmpty());
        assertTrue(lookups.toString(), lookups.isEmpty());
    }

    @Test
    public void bothSendersGetEveryMessageWhileTheLegacyDeviceApiIsOn() {
        PushService push = service(true);
        assertTrue(push.isEnabled());
        push.notifyDevicesOnUpdate(1);
        push.notifyDeviceOnSettingUpdate(8);
        assertEquals(Arrays.asList(
                "mqtt:7:" + PushMessage.TYPE_CONFIG_UPDATED, "polling:7:" + PushMessage.TYPE_CONFIG_UPDATED,
                "mqtt:8:" + PushMessage.TYPE_CONFIG_UPDATED, "polling:8:" + PushMessage.TYPE_CONFIG_UPDATED), sent);
    }

    @Test
    public void theSwitchIsBoundFromTheServletContext() {
        assertFalse(bound(new HashMap<>()));
        Map<String, String> on = new HashMap<>();
        on.put(LegacyDeviceApiFilter.PARAMETER, "1");
        assertTrue(bound(on));
        Map<String, String> off = new HashMap<>();
        off.put(LegacyDeviceApiFilter.PARAMETER, "0");
        assertFalse(bound(off));
    }

    private static boolean bound(Map<String, String> params) {
        ServletContext context = (ServletContext) Proxy.newProxyInstance(
                PushServiceLegacySwitchTest.class.getClassLoader(), new Class<?>[]{ServletContext.class},
                (proxy, method, args) -> method.getName().equals("getInitParameter") ? params.get((String) args[0]) : null);
        Injector injector = Guice.createInjector(new NotificationMqttConfigModule(context));
        return injector.getInstance(Key.get(Boolean.class, Names.named(LegacyDeviceApiFilter.PARAMETER)));
    }
}
