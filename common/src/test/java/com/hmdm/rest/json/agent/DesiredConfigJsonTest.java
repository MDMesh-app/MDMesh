package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class DesiredConfigJsonTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void nulls_are_omitted_and_unknown_fields_tolerated() throws Exception {
        DesiredConfig c = new DesiredConfig();
        c.setConfigurationId(7);
        Map<String, Boolean> p = new LinkedHashMap<>();
        p.put("wifi", true);
        c.setPolicies(p);
        String json = mapper.writeValueAsString(c);
        assertFalse("kiosk must be omitted when null", json.contains("kiosk"));
        assertTrue(json.contains("\"wifi\":true"));

        DesiredConfig back = mapper.readValue("{\"configurationId\":7,\"future\":1,\"kiosk\":{\"mode\":\"single\",\"newKey\":true}}", DesiredConfig.class);
        assertEquals(Integer.valueOf(7), back.getConfigurationId());
        assertEquals("single", back.getKiosk().getMode());
    }
}
