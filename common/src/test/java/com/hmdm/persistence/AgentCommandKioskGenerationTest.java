package com.hmdm.persistence;

import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.mapper.AgentCommandMapper;
import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.persistence.mapper.DeviceEventMapper;
import com.hmdm.persistence.mapper.DeviceStateMapper;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Regression coverage for durable kiosk ordering: no device clock or paged event history participates. */
public class AgentCommandKioskGenerationTest {

    @Test
    public void kioskChangingCommandsAdvanceOnlyWhenDelivered() {
        List<String> calls = new ArrayList<>();
        AgentCommandDAO dao = dao(calls);

        AgentCommand enter = command("kiosk.enter");
        AgentCommand exit = command("kiosk.exit");
        AgentCommand apply = command("config.apply");
        AgentCommand ring = command("ring");
        dao.insert(enter);
        dao.insert(exit);
        dao.insert(apply);
        dao.insert(ring);

        assertEquals(java.util.Arrays.asList(
                "command.insert:kiosk.enter", "command.insert:kiosk.exit",
                "command.insert:config.apply", "command.insert:ring"), calls);

        dao.claimForDelivery(enter, 1L);
        dao.claimForDelivery(exit, 2L);
        dao.claimForDelivery(apply, 3L);
        dao.claimForDelivery(ring, 4L);
        assertEquals(java.util.Arrays.asList(
                "command.insert:kiosk.enter", "command.insert:kiosk.exit",
                "command.insert:config.apply", "command.insert:ring",
                "state.advance:d", "state.advance:d", "state.advance:d"), calls);
    }

    @Test
    public void kioskReturnStoresAuditAndCurrentGenerationTogether() {
        List<String> calls = new ArrayList<>();
        AgentCommandDAO dao = dao(calls);

        dao.recordKioskReturn("d", 123L, "returned on-device");

        assertEquals(java.util.Arrays.asList("event.insert:kioskReturn", "state.return:d"), calls);
    }

    @Test
    public void bufferedReturnPrecedesTheCustomKioskDeliveryThatInvalidatesIt() {
        List<String> calls = new ArrayList<>();
        AgentCommandDAO dao = dao(calls);
        AgentCommand custom = command("kiosk.enter");

        // An admin can queue Custom kiosk while an offline device has an earlier kioskReturn buffered.
        dao.insert(custom);
        dao.recordKioskReturn("d", 123L, "returned on-device");
        dao.claimForDelivery(custom, 456L);

        assertEquals(java.util.Arrays.asList(
                "command.insert:kiosk.enter", "event.insert:kioskReturn", "state.return:d", "state.advance:d"), calls);
    }

    private static AgentCommand command(String type) {
        AgentCommand c = new AgentCommand();
        c.setDeviceNumber("d");
        c.setType(type);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static AgentCommandDAO dao(final List<String> calls) {
        AgentCommandMapper commands = (AgentCommandMapper) Proxy.newProxyInstance(
                AgentCommandMapper.class.getClassLoader(), new Class[]{AgentCommandMapper.class}, (p, m, a) -> {
                    if ("insert".equals(m.getName())) calls.add("command.insert:" + ((AgentCommand) a[0]).getType());
                    if ("claimForDelivery".equals(m.getName())) return 1;
                    return defaultValue(m.getReturnType());
                });
        DeviceStateMapper states = (DeviceStateMapper) Proxy.newProxyInstance(
                DeviceStateMapper.class.getClassLoader(), new Class[]{DeviceStateMapper.class}, (p, m, a) -> {
                    if ("advanceKioskGeneration".equals(m.getName())) calls.add("state.advance:" + a[0]);
                    if ("recordKioskReturn".equals(m.getName())) calls.add("state.return:" + a[0]);
                    return defaultValue(m.getReturnType());
                });
        DeviceEventMapper events = (DeviceEventMapper) Proxy.newProxyInstance(
                DeviceEventMapper.class.getClassLoader(), new Class[]{DeviceEventMapper.class}, (p, m, a) -> {
                    if ("insert".equals(m.getName())) calls.add("event.insert:" + ((com.hmdm.persistence.domain.DeviceEvent) a[0]).getType());
                    return defaultValue(m.getReturnType());
                });
        AgentDeviceMapper devices = (AgentDeviceMapper) Proxy.newProxyInstance(
                AgentDeviceMapper.class.getClassLoader(), new Class[]{AgentDeviceMapper.class}, (p, m, a) -> defaultValue(m.getReturnType()));
        return new AgentCommandDAO(commands, devices, states, events);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return 0;
    }
}
