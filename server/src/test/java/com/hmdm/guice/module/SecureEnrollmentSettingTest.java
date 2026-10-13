package com.hmdm.guice.module;

import com.hmdm.rest.filter.LegacyDeviceApiFilter;
import org.junit.Test;

import javax.servlet.ServletContext;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code secure.enrollment} makes {@code /files/*} demand the Headwind launcher's request signature, which the MDMesh
 * agent never sends, so the setting only takes effect together with the legacy device API.
 */
public class SecureEnrollmentSettingTest {

    @Test
    public void itIsOffByDefault() {
        assertFalse(ConfigureModule.secureEnrollment(context(new HashMap<>())));
    }

    @Test
    public void itIsIgnoredWhileTheLegacyDeviceApiIsOff() {
        Map<String, String> p = new HashMap<>();
        p.put("secure.enrollment", "1");
        assertFalse(ConfigureModule.secureEnrollment(context(p)));
        p.put(LegacyDeviceApiFilter.PARAMETER, "0");
        assertFalse(ConfigureModule.secureEnrollment(context(p)));
    }

    @Test
    public void itStillWorksForLegacyLauncherFleets() {
        Map<String, String> p = new HashMap<>();
        p.put("secure.enrollment", "true");
        p.put(LegacyDeviceApiFilter.PARAMETER, "1");
        assertTrue(ConfigureModule.secureEnrollment(context(p)));
        p.put("secure.enrollment", "0");
        assertFalse(ConfigureModule.secureEnrollment(context(p)));
    }

    private static ServletContext context(Map<String, String> params) {
        return (ServletContext) Proxy.newProxyInstance(SecureEnrollmentSettingTest.class.getClassLoader(),
                new Class<?>[]{ServletContext.class},
                (proxy, method, args) -> method.getName().equals("getInitParameter") ? params.get((String) args[0]) : null);
    }
}
