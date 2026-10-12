package com.hmdm.plugins;

import com.google.inject.Guice;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.plugins.push.persistence.PushScheduleDAO;
import com.hmdm.plugins.push.persistence.domain.PluginPushSchedule;
import com.hmdm.plugins.push.rest.PushResource;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Scheduled pushes run later without a user, so wide scopes are only for users who can reach every device. */
public class PushScheduleScopeTest {

    private final List<String> saved = new ArrayList<>();

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private PushResource resource() {
        DeviceDAO deviceDAO = new DeviceDAO(new FakeMapper<>(DeviceMapper.class).mapper, null,
                Guice.createInjector(), null, 3);
        PushScheduleDAO schedule = new PushScheduleDAO(null) {
            @Override public void insert(PluginPushSchedule t) { saved.add(t.getScope()); }
            @Override public void update(PluginPushSchedule t) { saved.add(t.getScope()); }
        };
        return new PushResource(null, schedule, null, deviceDAO, null, null);
    }

    private static PluginPushSchedule task(String scope) {
        PluginPushSchedule t = new PluginPushSchedule();
        t.setScope(scope);
        return t;
    }

    private static void login(boolean allDevices) {
        User u = Fakes.user(9, 1, "plugin_push_delete");
        u.setAllDevicesAvailable(allDevices);
        SecurityContext.release();
        SecurityContext.init(u);
    }

    @Test
    public void aGroupLimitedUserCannotScheduleWideScopes() {
        login(false);
        PushResource resource = resource();
        for (String scope : new String[]{"all", "group", "configuration"}) {
            assertNotEquals(scope, Response.ResponseStatus.OK, resource.saveTask(task(scope)).getStatus());
        }
        assertTrue(saved.toString(), saved.isEmpty());
    }

    @Test
    public void aUserWithAllDevicesMayScheduleWideScopes() {
        login(true);
        assertEquals(Response.ResponseStatus.OK, resource().saveTask(task("all")).getStatus());
        assertEquals(1, saved.size());
    }
}
