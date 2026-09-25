package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.AgentCommand;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class CommandHistoryViewTest {

    private static AgentCommand cmd(String type, String payload) {
        AgentCommand c = new AgentCommand();
        c.setId(7); c.setDeviceNumber("dev-1"); c.setType(type); c.setPayload(payload);
        c.setRequiresCapability("device.configApply");
        c.setStatus("done"); c.setCreatedAt(10L); c.setDeliveredAt(20L); c.setCompletedAt(30L); c.setDetail("ok");
        return c;
    }

    @Test public void copies_lifecycle_fields() {
        CommandHistoryView v = CommandHistoryView.from(cmd("device.lock", "{}"));
        assertEquals(Integer.valueOf(7), v.getId());
        assertEquals("device.lock", v.getType());
        assertEquals("done", v.getStatus());
        assertEquals("ok", v.getDetail());
        assertEquals(Long.valueOf(10L), v.getCreatedAt());
        assertEquals(Long.valueOf(20L), v.getDeliveredAt());
        assertEquals(Long.valueOf(30L), v.getCompletedAt());
        assertNull(v.getSubject());
    }

    @Test public void serialises_exactly_the_view_fields_and_never_the_payload() throws Exception {
        String secret = "{\"kiosk\":{\"password\":\"SECRET-HASH\"},\"revision\":\"abc\"}";
        String json = new ObjectMapper().writeValueAsString(CommandHistoryView.from(cmd("config.apply", secret)));
        assertFalse(json, json.contains("SECRET-HASH"));
        List<String> keys = new java.util.ArrayList<String>();
        new ObjectMapper().readTree(json).fieldNames().forEachRemaining(keys::add);
        Collections.sort(keys);
        // no payload, deviceNumber or requiresCapability key; null subject omitted (not "subject":null)
        assertEquals(Arrays.asList("completedAt", "createdAt", "deliveredAt", "detail", "id", "status", "type"), keys);
    }

    @Test public void subject_is_the_package_for_app_commands() {
        assertEquals("com.example", CommandHistoryView.subjectOf("app.install", "{\"packageName\":\"com.example\",\"url\":\"https://x/a.apk\"}"));
        assertEquals("com.example", CommandHistoryView.subjectOf("app.uninstall", "{\"packageName\":\"com.example\"}"));
        String json;
        try {
            json = new ObjectMapper().writeValueAsString(CommandHistoryView.from(cmd("app.uninstall", "{\"packageName\":\"com.example\"}")));
        } catch (Exception e) { throw new AssertionError(e); }
        assertTrue(json, json.contains("\"subject\":\"com.example\""));
    }

    @Test public void subject_null_for_other_types_even_with_packageName() {
        assertNull(CommandHistoryView.subjectOf("app.launch", "{\"packageName\":\"com.example\"}"));
        assertNull(CommandHistoryView.subjectOf("App.Install", "{\"packageName\":\"com.example\"}"));
        assertNull(CommandHistoryView.subjectOf(" app.install", "{\"packageName\":\"com.example\"}"));
        assertNull(CommandHistoryView.subjectOf(null, "{\"packageName\":\"com.example\"}"));
    }

    @Test public void subject_null_when_the_payload_has_no_usable_package() {
        // Full parsing rules are pinned in CommandPayloadsTest; here: the view delegates and never throws.
        assertNull(CommandHistoryView.subjectOf("app.install", null));
        assertNull(CommandHistoryView.subjectOf("app.install", "not json"));
        assertNull(CommandHistoryView.subjectOf("app.install", "{\"packageName\":42}"));
        assertNull(CommandHistoryView.from(cmd("app.install", "{broken")).getSubject());
    }

    @Test public void fromAll_maps_in_order_and_handles_empty_and_null() {
        assertEquals(Collections.emptyList(), CommandHistoryView.fromAll(null));
        assertEquals(Collections.emptyList(), CommandHistoryView.fromAll(Collections.<AgentCommand>emptyList()));
        List<CommandHistoryView> out = CommandHistoryView.fromAll(Arrays.asList(
                cmd("app.uninstall", "{\"packageName\":\"com.a\"}"), cmd("device.lock", null)));
        assertEquals(2, out.size());
        assertEquals("com.a", out.get(0).getSubject());
        assertEquals("device.lock", out.get(1).getType());
        assertNull(out.get(1).getSubject());
    }
}
