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
    public void kioskChangingCommandsAdvanceAndInvalidateTheServerOwnedGeneration() {
        List<String> calls = new ArrayList<>();
        AgentCommandDAO dao = dao(calls);

        dao.insert(command("kiosk.enter"));
        dao.insert(command("kiosk.exit"));
        dao.insert(command("config.apply"));
        dao.insert(command("ring"));

        assertEquals(java.util.Arrays.asList(
                "command.insert:kiosk.enter", "state.advance:d",
                "command.insert:kiosk.exit", "state.advance:d",
                "command.insert:config.apply", "state.advance:d",
                "command.insert:ring"), calls);
    }

    @Test
    public void configuredStartStoresAuditAndCurrentGenerationTogether() {
        List<String> calls = new ArrayList<>();
        AgentCommandDAO dao = dao(calls);

        dao.recordConfiguredKioskStart("d", 123L, "local start");

        assertEquals(java.util.Arrays.asList("event.insert:configuredKioskStart", "state.record:d"), calls);
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
                    return defaultValue(m.getReturnType());
                });
        DeviceStateMapper states = (DeviceStateMapper) Proxy.newProxyInstance(
                DeviceStateMapper.class.getClassLoader(), new Class[]{DeviceStateMapper.class}, (p, m, a) -> {
                    if ("advanceKioskGeneration".equals(m.getName())) calls.add("state.advance:" + a[0]);
                    if ("recordConfiguredKioskStart".equals(m.getName())) calls.add("state.record:" + a[0]);
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
