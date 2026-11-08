package com.hmdm.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.persistence.domain.Configuration;
import org.junit.Test;

import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * configurations.extensions on the wire and in the database: a save without the key (an older client) or with null
 * leaves the field null, which the update statement reads as "keep what is stored"; a copy takes its own copy.
 */
public class ConfigurationExtensionsJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void aSaveWithoutExtensionsOrWithNullLeavesItNull() throws Exception {
        assertNull(JSON.readValue("{\"id\":3,\"name\":\"w\"}", Configuration.class).getExtensions());
        assertNull(JSON.readValue("{\"id\":3,\"name\":\"w\",\"extensions\":null}", Configuration.class).getExtensions());
    }

    @Test
    public void aSaveWithExtensionsCarriesThemWhole() throws Exception {
        Configuration c = JSON.readValue("{\"name\":\"w\",\"extensions\":{\"demo\":{\"a\":[1,2]},\"demo.more\":{}}}",
                Configuration.class);
        assertEquals("{\"demo\":{\"a\":[1,2]},\"demo.more\":{}}", JSON.writeValueAsString(c.getExtensions()));
        assertEquals(0, JSON.readValue("{\"name\":\"w\",\"extensions\":{}}", Configuration.class).getExtensions().size());
    }

    @Test
    public void theReadConfigurationShowsThemAsAnObject() throws Exception {
        Configuration c = new Configuration();
        c.setExtensions((ObjectNode) JSON.readTree("{\"demo\":{\"on\":true}}"));
        assertTrue(JSON.writeValueAsString(c).contains("\"extensions\":{\"demo\":{\"on\":true}}"));
    }

    @Test
    public void aCopyHasItsOwnExtensions() throws Exception {
        Configuration c = new Configuration();
        c.setExtensions((ObjectNode) JSON.readTree("{\"demo\":{\"on\":true}}"));
        Configuration copy = c.newCopy();
        assertEquals(c.getExtensions(), copy.getExtensions());
        assertNotSame(c.getExtensions(), copy.getExtensions());
        ((ObjectNode) copy.getExtensions().get("demo")).put("on", false);
        assertEquals(true, c.getExtensions().get("demo").get("on").asBoolean());
        assertNull(new Configuration().newCopy().getExtensions());
    }

    @Test
    public void theTypeHandlerReadsOnlyJsonObjects() throws Exception {
        assertNull(JsonObjectTypeHandler.parse(null));
        assertEquals("{\"demo\":{}}", JSON.writeValueAsString(JsonObjectTypeHandler.parse("{\"demo\": {}}")));
        for (String bad : new String[]{"[]", "\"text\"", "{"}) {
            try {
                JsonObjectTypeHandler.parse(bad);
                fail(bad + " was read");
            } catch (SQLException expected) {
                // refused
            }
        }
    }
}
