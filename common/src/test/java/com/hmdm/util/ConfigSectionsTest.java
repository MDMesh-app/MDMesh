package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.hmdm.rest.json.agent.DesiredAppPermissions;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.json.agent.DesiredConnectivity;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConfigSectionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** proto/config-sections.json is the registry, row for row and in order. */
    @Test
    public void theRegistryIsTheLedger() throws Exception {
        Path path = Paths.get("..", "proto", "config-sections.json");
        if (!Files.exists(path)) path = Paths.get("proto", "config-sections.json");
        List<String> ledger = new ArrayList<>();
        for (JsonNode row : MAPPER.readTree(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).get("sections")) {
            ledger.add(row.get("name").asText() + " " + row.get("since").asText() + " "
                    + (row.has("capability") ? row.get("capability").asText() : null));
        }
        List<String> registry = new ArrayList<>();
        for (ConfigSections.Section s : ConfigSections.ALL) {
            registry.add(s.getName() + " " + s.getSince() + " " + s.getCapability());
        }
        assertEquals(ledger, registry);
    }

    /** Every section the document can carry has a row: a new one without a row would reach every agent ungated. */
    @Test
    public void everySectionOfTheDocumentIsRegistered() {
        assertEquals(Collections.<String>emptyList(), unregistered(DesiredConfig.class));
    }

    @Test
    public void aSectionWithoutARowIsCaught() {
        assertEquals(Collections.singletonList("privateDns"), unregistered(WithAnotherSection.class));
    }

    /** A test-only document with a section nobody registered. */
    static class WithAnotherSection extends DesiredConfig {
        private String privateDns;
        public String getPrivateDns() { return privateDns; }
    }

    /** The serialised properties of {@code type} that are neither a registry row nor revision/configurationId. */
    private static List<String> unregistered(Class<?> type) {
        List<String> out = new ArrayList<>();
        for (BeanPropertyDefinition p : MAPPER.getSerializationConfig().introspect(MAPPER.constructType(type)).findProperties()) {
            String name = p.getName();
            if (name.equals("revision") || name.equals("configurationId")) continue;
            boolean registered = false;
            for (ConfigSections.Section s : ConfigSections.ALL) registered |= s.getName().equals(name);
            if (!registered) out.add(name);
        }
        return out;
    }

    /** Each row reads and clears its own field: stripping one leaves the rest, and the revision is of what is left. */
    @Test
    public void eachRowStripsItsOwnSection() {
        for (ConfigSections.Section s : ConfigSections.ALL) {
            DesiredConfig doc = everySection();
            String full = doc.getRevision();
            String without = ConfigSections.revisionWithout(doc, Collections.singletonList(s));
            assertEquals("revisionWithout leaves the document as it was", full, doc.getRevision());
            assertEquals(DesiredConfigBuilder.canonicalJson(everySection()), DesiredConfigBuilder.canonicalJson(doc));
            ConfigSections.strip(doc, Collections.singletonList(s));
            assertNull(s.getName(), s.valueIn(doc));
            for (ConfigSections.Section other : ConfigSections.ALL) {
                if (other != s) assertFalse(s.getName() + " left " + other.getName(), other.valueIn(doc) == null);
            }
            assertNotEquals(s.getName(), full, doc.getRevision());
            assertEquals(s.getName(), without, doc.getRevision());
        }
    }

    private static DesiredConfig everySection() {
        DesiredConfig doc = DesiredConfigBuilder.build(kioskConfiguration(), Collections.emptyList(), null);
        DesiredConnectivity c = new DesiredConnectivity();
        c.setMode(DesiredConfigBuilder.CONNECTIVITY_ALWAYS_ON);
        doc.setConnectivity(c);
        DesiredAppPermissions p = new DesiredAppPermissions();
        p.setPackageName("org.example");
        p.setPermissions(new TreeMap<>(Collections.singletonMap("android.permission.CAMERA", "deny")));
        doc.setAppPermissions(Collections.singletonList(p));
        doc.setRevision(DesiredConfigBuilder.revision(doc));
        for (ConfigSections.Section s : ConfigSections.ALL) assertFalse(s.getName(), s.valueIn(doc) == null);
        return doc;
    }

    private static com.hmdm.persistence.domain.Configuration kioskConfiguration() {
        com.hmdm.persistence.domain.Configuration cfg = new com.hmdm.persistence.domain.Configuration();
        cfg.setId(1);
        cfg.setKioskMode(true);
        cfg.setCamera(false);
        return cfg;
    }

    @Test
    public void onlyAReadableVersionIsBelow() {
        assertTrue(ConfigSections.isBelow("0.3.8", "0.4.0"));
        assertTrue(ConfigSections.isBelow("0.3.8-rc1", "0.4.0"));
        assertTrue(ConfigSections.isBelow("v0.3", "0.4.0"));
        assertTrue(ConfigSections.isBelow("0.9.9", "0.10.0"));
        for (String v : Arrays.asList(null, "", "x", "0.4.0", "0.4.0-rc1", "0.4.0-dev", "0.4.0+fork", "0.5.1-myfork",
                "0.10.0", "1.0", "0.3.8.1", "0.3.x", "fork-0.3.8", "99999999999.0.0")) {
            assertFalse(v, ConfigSections.isBelow(v, "0.4.0"));
        }
    }

    @Test
    public void emptyAsksNothing() {
        assertTrue(ConfigSections.isEmpty(null));
        assertTrue(ConfigSections.isEmpty(Collections.emptyList()));
        assertTrue(ConfigSections.isEmpty(Collections.emptyMap()));
        assertFalse(ConfigSections.isEmpty(Collections.singletonList(1)));
        assertFalse(ConfigSections.isEmpty(new DesiredConnectivity()));
    }
}
