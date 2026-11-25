package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** proto/config-sections.json is well formed and lists exactly the sections of the config.apply schema. */
public class ConfigSectionsLedgerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("name", "since", "capability", "label"));
    /** Top-level keys of the document that are not sections. */
    private static final Set<String> NOT_SECTIONS = new HashSet<>(Arrays.asList("revision", "configurationId"));

    @Test
    public void everyRowIsWellFormed() throws Exception {
        Set<String> releases = releases(read("VERSIONING.md"));
        JsonNode rows = MAPPER.readTree(read("config-sections.json")).get("sections");
        assertTrue("a sections array", rows != null && rows.isArray() && rows.size() > 0);
        Set<String> names = new HashSet<>();
        for (JsonNode row : rows) {
            for (Iterator<String> it = row.fieldNames(); it.hasNext(); ) {
                String field = it.next();
                assertTrue(row + ": unknown field " + field, FIELDS.contains(field));
            }
            String name = text(row, "name");
            assertTrue(row + ": name is a document key", name.matches("[a-z][A-Za-z0-9]*"));
            assertTrue(row + ": name is listed once", names.add(name));
            String since = text(row, "since");
            assertTrue(row + ": since is a release, MAJOR.MINOR.PATCH", since.matches("\\d+\\.\\d+\\.\\d+"));
            assertTrue(row + ": since is a release of a VERSIONING.md row", releases.contains("v" + since));
            if (row.has("capability")) {
                assertTrue(row + ": capability is a token", text(row, "capability").matches("[a-z]+\\.[a-z][A-Za-z0-9]*"));
            }
            assertEquals(row + ": label", "configSection." + name, text(row, "label"));
        }
    }

    @Test
    public void theRowsAreTheSectionsOfTheConfigApplySchema() throws Exception {
        Set<String> ledger = new LinkedHashSet<>();
        for (JsonNode row : MAPPER.readTree(read("config-sections.json")).get("sections")) ledger.add(row.get("name").asText());
        Set<String> schema = new LinkedHashSet<>();
        JsonNode properties = MAPPER.readTree(read("payloads/config-apply.schema.json")).get("properties");
        for (Iterator<String> it = properties.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!NOT_SECTIONS.contains(key)) schema.add(key);
        }
        assertEquals(schema, ledger);
    }

    @Test
    public void releasesAreReadFromTheReleaseColumn() {
        String doc = "| version | release | notes |\n|---|---|---|\n| 1.1 | v0.3.0 | a |\n| 1.12 | v0.6.0 | b, v0.9.9 |\n";
        assertEquals(new HashSet<>(Arrays.asList("v0.3.0", "v0.6.0")), releases(doc));
    }

    /** The release column of VERSIONING.md's rows. */
    static Set<String> releases(String versioning) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("(?m)^\\|\\s*\\d+\\.\\d+\\s*\\|\\s*(v[^|\\s]+)\\s*\\|").matcher(versioning);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        assertTrue(row + ": " + field + " is a non-empty string", value != null && value.isTextual() && !value.asText().isEmpty());
        return value.asText();
    }

    private static String read(String name) throws Exception {
        Path path = Paths.get("..", "proto", name);
        if (!Files.exists(path)) path = Paths.get("proto", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
