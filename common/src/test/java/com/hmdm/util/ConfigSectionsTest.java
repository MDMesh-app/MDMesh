package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.rest.json.agent.DesiredAppPermissions;
import com.hmdm.rest.json.agent.DesiredConfig;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ConfigSectionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** proto/config-sections.json is the registry, row for row and in order. */
    @Test
    public void theRegistryIsTheLedger() throws Exception {
        Path path = Paths.get("..", "proto", "config-sections.json");
        if (!Files.exists(path)) path = Paths.get("proto", "config-sections.json");
        List<String> ledger = new ArrayList<>();
        for (JsonNode row : MAPPER.readTree(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).get("sections")) {
            ledger.add(row.get("name").asText() + " " + (row.has("capability") ? row.get("capability").asText() : null));
        }
        List<String> registry = new ArrayList<>();
        for (ConfigSections.Section<?> s : ConfigSections.ALL) registry.add(s.name + " " + s.capability);
        assertEquals(ledger, registry);
    }

    /** Every section a document can carry has a row: a new one without a row would skip the gate. */
    @Test
    public void everySectionOfTheDocumentHasARow() {
        assertEquals(Collections.<String>emptySet(), withoutARow(DesiredConfig.class));
        assertEquals(Collections.singleton("privateDns"), withoutARow(WithAnotherSection.class));
    }

    /** A document with a section nobody registered. */
    static class WithAnotherSection extends DesiredConfig {
        public String getPrivateDns() { return null; }
    }

    private static Set<String> withoutARow(Class<?> type) {
        Set<String> out = new HashSet<>();
        for (BeanPropertyDefinition p : MAPPER.getSerializationConfig().introspect(MAPPER.constructType(type)).findProperties()) {
            out.add(p.getName());
        }
        out.removeAll(Arrays.asList("revision", "configurationId"));
        for (ConfigSections.Section<?> s : ConfigSections.ALL) out.remove(s.name);
        return out;
    }

    /** Each row's field is its own: cleared, exactly its key leaves the document, under the revision of the rest. */
    @Test
    public void eachRowIsItsOwnSection() throws Exception {
        for (ConfigSections.Section<?> s : ConfigSections.ALL) {
            DesiredConfig doc = everySection();
            Map<String, Object> rest = new TreeMap<>(MAPPER.convertValue(doc, Map.class));
            assertNotNull(s.name, rest.remove(s.name));
            rest.remove("revision");

            String without = ConfigSections.revisionWithout(doc, Collections.<ConfigSections.Section<?>>singletonList(s));
            assertEquals("left as it was", DesiredConfigBuilder.toPayloadJson(everySection()), DesiredConfigBuilder.toPayloadJson(doc));

            s.clear(doc);
            assertEquals(s.name, MAPPER.readTree(MAPPER.writeValueAsString(rest)), MAPPER.readTree(DesiredConfigBuilder.canonicalJson(doc)));
            assertEquals(s.name, DesiredConfigBuilder.revision(doc), without);
        }
    }

    /** Withheld: exactly the sections with a capability the device lacks. No other token, or lack of one, counts. */
    @Test
    public void onlyAMissingCapabilityWithholdsASection() {
        Set<String> capabilities = new HashSet<>();
        for (ConfigSections.Section<?> s : ConfigSections.ALL) if (s.capability != null) capabilities.add(s.capability);
        for (Set<String> other : Arrays.asList(Collections.<String>emptySet(), Collections.singleton("config.policies"),
                new HashSet<>(Arrays.asList("config.appPermissions", "config.none", DesiredConfigBuilder.CAPABILITY)))) {
            Set<String> all = new HashSet<>(other);
            all.addAll(capabilities);
            assertEquals(Collections.emptyList(), ConfigSections.withheld(everySection(), all));
            for (ConfigSections.Section<?> s : ConfigSections.ALL) {
                if (s.capability == null) continue;
                Set<String> lacking = new HashSet<>(all);
                lacking.remove(s.capability);
                assertEquals(Collections.singletonList(s), ConfigSections.withheld(everySection(), lacking));
            }
        }
        assertEquals("a section the document doesn't have is not withheld", Collections.emptyList(),
                ConfigSections.withheld(new DesiredConfig(), null));
    }

    private static DesiredConfig everySection() {
        Configuration cfg = new Configuration();
        cfg.setId(1);
        cfg.setKioskMode(true);
        cfg.setCamera(false);
        cfg.setConnectivityMode(DesiredConfigBuilder.CONNECTIVITY_ALWAYS_ON);
        DesiredConfig doc = DesiredConfigBuilder.build(cfg, Collections.<Application>emptyList(), null);
        DesiredAppPermissions p = new DesiredAppPermissions();
        p.setPackageName("org.example");
        p.setPermissions(new TreeMap<>(Collections.singletonMap("android.permission.CAMERA", "deny")));
        doc.setAppPermissions(Collections.singletonList(p));
        doc.setRevision(DesiredConfigBuilder.revision(doc));
        return doc;
    }
}
