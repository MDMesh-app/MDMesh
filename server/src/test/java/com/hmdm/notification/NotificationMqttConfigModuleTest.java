package com.hmdm.notification;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import com.hmdm.notification.guice.module.NotificationMqttConfigModule;
import com.hmdm.rest.filter.LegacyDeviceApiFilter;
import org.junit.Test;

import javax.servlet.ServletContext;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the MQTT settings {@link NotificationMqttConfigModule} binds: the broker admin password is never a value baked
 * into the code (an unset one is generated per start), and the embedded broker, which only the Headwind launcher
 * speaks, stays off unless the legacy device API is turned on. The servlet context is a {@link Proxy} fake.
 */
public class NotificationMqttConfigModuleTest {

    @Test
    public void anUnsetAdminPasswordIsGeneratedPerStart() {
        String first = adminPassword(new HashMap<>());
        String second = adminPassword(new HashMap<>());
        assertNotEquals("dd3V5YDkrX", first);
        assertTrue("long enough to resist guessing: " + first, first.length() >= 32);
        assertNotEquals(first, second);
    }

    @Test
    public void anEmptyAdminPasswordIsGeneratedToo() {
        Map<String, String> params = new HashMap<>();
        params.put("mqtt.admin.password", "");
        assertTrue(adminPassword(params).length() >= 32);
    }

    @Test
    public void aConfiguredAdminPasswordIsKept() {
        Map<String, String> params = new HashMap<>();
        params.put("mqtt.admin.password", "configured-secret");
        assertEquals("configured-secret", adminPassword(params));
    }

    @Test
    public void theBrokerStaysOffWhileTheLegacyDeviceApiIsOff() {
        Map<String, String> params = new HashMap<>();
        params.put("mqtt.server.uri", "0.0.0.0:31000");
        assertEquals("", serverUri(params));
        params.put(LegacyDeviceApiFilter.PARAMETER, "0");
        assertEquals("", serverUri(params));
    }

    @Test
    public void theBrokerUriIsKeptWhenTheLegacyDeviceApiIsOn() {
        Map<String, String> params = new HashMap<>();
        params.put("mqtt.server.uri", "0.0.0.0:31000");
        params.put(LegacyDeviceApiFilter.PARAMETER, "1");
        assertEquals("0.0.0.0:31000", serverUri(params));
    }

    @Test
    public void anExternalBrokerWithAuthButNoPasswordIsNotUsed() {
        Map<String, String> params = new HashMap<>();
        params.put("mqtt.server.uri", "broker.example:1883");
        params.put(LegacyDeviceApiFilter.PARAMETER, "1");
        params.put("mqtt.external", "1");
        params.put("mqtt.auth", "1");
        assertEquals("", serverUri(params));
        params.put("mqtt.admin.password", "");
        assertEquals("", serverUri(params));
    }

    @Test
    public void anExternalBrokerWithItsPasswordOrWithoutAuthIsUsed() {
        Map<String, String> params = new HashMap<>();
        params.put("mqtt.server.uri", "broker.example:1883");
        params.put(LegacyDeviceApiFilter.PARAMETER, "1");
        params.put("mqtt.external", "true");
        assertEquals("broker.example:1883", serverUri(params));
        params.put("mqtt.auth", "1");
        params.put("mqtt.admin.password", "configured-secret");
        assertEquals("broker.example:1883", serverUri(params));
        assertEquals("configured-secret", adminPassword(params));
    }

    private static String adminPassword(Map<String, String> params) {
        return injector(params).getInstance(Key.get(String.class, Names.named("mqtt.admin.password")));
    }

    private static String serverUri(Map<String, String> params) {
        return injector(params).getInstance(Key.get(String.class, Names.named("mqtt.server.uri")));
    }

    private static Injector injector(Map<String, String> params) {
        ServletContext context = (ServletContext) Proxy.newProxyInstance(
                ServletContext.class.getClassLoader(), new Class<?>[]{ServletContext.class},
                (p, m, a) -> m.getName().equals("getInitParameter") ? params.get((String) a[0]) : null);
        return Guice.createInjector(new NotificationMqttConfigModule(context));
    }
}
