/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.plugins;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.hmdm.plugins.audit.guice.module.AuditPersistenceModule;
import com.hmdm.plugins.audit.guice.module.AuditScrubTaskModule;
import com.hmdm.plugins.audit.persistence.AuditDAO;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.servlet.ServletContext;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The background scrub of the audit records older releases stored (AuditDAO.scrubOldRecords, the policy in
 * StoredPayloads) on real PostgreSQL: secrets become masks or markers and the rest of each record stays, a body that
 * can't be parsed is replaced, a record written after the upgrade is left alone, it goes in batches from its stored
 * progress, and a second run rewrites no row.
 *
 * <p>Runs when MDMESH_TEST_PG_URL is set (see AgentEnrollmentTransactionTest); with MDMESH_TEST_PG_REQUIRED it fails
 * without one. Works in its own schema, dropped afterwards. With MDMESH_TEST_SCRUB_ROWS=n it also times the scrub of
 * n synthetic records (for UPGRADING).</p>
 */
public class AuditLogScrubTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");
    private static final String NOT_STORED = "\nBody: (not valid JSON, not stored)";
    private static final String UA = "\nUser-Agent: Mozilla/5.0";

    private static String schema;
    private static String url;
    private static AuditDAO dao;
    private static com.hmdm.plugins.audit.persistence.mapper.AuditMapper mapper;

    @BeforeClass
    public static void setUpClass() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        schema = "audit_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        url = URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        for (String changelog : new String[]{"liquibase/db.changelog.xml", "liquibase/plugin.changelog.xml",
                "liquibase/audit.changelog.xml"}) {
            try (Connection c = connect()) {
                Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
                new Liquibase(changelog, new ClassLoaderResourceAccessor(), db).update("common,private");
            }
        }
        Map<String, String> jdbc = new HashMap<>();
        jdbc.put("JDBC.driver", "org.postgresql.Driver");
        jdbc.put("JDBC.url", url);
        jdbc.put("JDBC.username", user());
        jdbc.put("JDBC.password", password());
        Injector injector = Guice.createInjector(new AuditPersistenceModule(servletContext(jdbc)));
        dao = injector.getInstance(AuditDAO.class);
        mapper = injector.getInstance(com.hmdm.plugins.audit.persistence.mapper.AuditMapper.class);
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (schema != null) {
            try (Connection c = DriverManager.getConnection(URL, user(), password());
                 Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    public void scrubsTheSecretsOldReleasesStoredAndKeepsTheRest() throws Exception {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("plugin.audit.action.update.configuration", head("PUT", "/rest/private/configurations")
                + "\nBody: {\"id\":4,\"name\":\"Lobby\",\"wifiPassword\":\"wifi-1\","
                + "\"applicationSettings\":[{\"name\":\"k\",\"value\":\"app-2\"}],\"qrParameters\":\"qr-3\"}" + UA);
        rows.put("plugin.audit.action.update.device", head("PUT", "/rest/private/devices")
                + "\nBody: {\"id\":9,\"configuration\":{\"wifiPassword\":\"wifi-5\"}}\nUser-Agent: fake" + UA);
        rows.put("plugin.audit.action.password.reset", head("PUT", "/rest/plugins/devicereset/private/password")
                + "\nBody: {\"deviceId\":3,\"password\":\"dev-6\"}");              // no User-Agent line
        rows.put("plugin.audit.action.update.user", head("PUT", "/rest/private/users")
                + "\nBody: {\"login\":\"rbk\",\"newPassword\":\"np-7\",\"WiFiPassWord\":\"wifi-8\","
                + "\"owner\":{\"authToken\":\"at-9\",\"x\":[{\"kioskPin\":\"key-10\"}]},\"pin\":1357,"
                + "\"keep\":\"kept-value\"}" + UA);
        rows.put("plugin.audit.action.update.application", head("PUT", "/rest/private/applications/android")
                + "\nBody: {\"pkg\":\"com.acme\",\"url\":\"https:\\/\\/ops:url-12@cdn.example\\/a.apk?v=2&token=tok-13\"}"
                + UA);
        rows.put("plugin.audit.action.update.design", head("POST", "/rest/private/settings/design")
                + "\nBody: {\"password\": \"not-json-14" + UA);
        rows.put("plugin.audit.action.update.language", head("POST", "/rest/private/settings/lang")
                + "\nBody: password=plain-15&lang=en" + UA);
        rows.put("plugin.audit.action.remove.device", null);
        rows.put("plugin.audit.action.update.group", head("PUT", "/rest/private/groups")); // no body

        reset();
        for (Map.Entry<String, String> row : rows.entrySet()) {
            insert(row.getKey(), row.getValue());
        }
        sql("UPDATE plugin_audit_scrub SET lastId = 0, upToId = (SELECT MAX(id) FROM plugin_audit_log)");
        // Written after the upgrade: masked when written, never scrubbed
        insert("plugin.audit.action.update.user", head("PUT", "/rest/private/users") + "\nBody: {\"note\":\"later-16\"}");

        scrubAll(2);
        Map<String, String> scrubbed = payloads();

        assertEquals(head("PUT", "/rest/private/configurations") + "\nBody: {\"id\":4,\"name\":\"Lobby\","
                + "\"wifiPassword\":\"<set>\",\"applicationSettings\":\"<1 items>\",\"qrParameters\":\"<set>\"}" + UA,
                scrubbed.get("plugin.audit.action.update.configuration"));
        assertEquals(head("PUT", "/rest/private/devices") + "\nBody: {\"id\":9,\"configuration\":\"<1 entries>\"}" + UA,
                scrubbed.get("plugin.audit.action.update.device"));
        assertEquals(head("PUT", "/rest/plugins/devicereset/private/password") + NOT_STORED,
                scrubbed.get("plugin.audit.action.password.reset"));
        assertEquals(head("PUT", "/rest/private/users") + "\nBody: {\"login\":\"rbk\",\"newPassword\":\"******\","
                + "\"WiFiPassWord\":\"******\",\"owner\":{\"authToken\":\"******\",\"x\":[{\"kioskPin\":\"******\"}]},"
                + "\"pin\":\"******\",\"keep\":\"kept-value\"}" + UA,
                scrubbed.get("plugin.audit.action.update.user"));
        assertEquals(head("PUT", "/rest/private/applications/android") + "\nBody: {\"pkg\":\"com.acme\","
                + "\"url\":\"https://******@cdn.example/a.apk?v=2&token=******\"}" + UA,
                scrubbed.get("plugin.audit.action.update.application"));
        assertEquals(head("POST", "/rest/private/settings/design") + NOT_STORED + UA,
                scrubbed.get("plugin.audit.action.update.design"));
        assertEquals(head("POST", "/rest/private/settings/lang") + NOT_STORED + UA,
                scrubbed.get("plugin.audit.action.update.language"));
        assertNull(scrubbed.get("plugin.audit.action.remove.device"));
        assertEquals(head("PUT", "/rest/private/groups"), scrubbed.get("plugin.audit.action.update.group"));
        assertTrue(text("SELECT payload FROM plugin_audit_log ORDER BY id DESC LIMIT 1").contains("later-16"));
        for (String secret : new String[]{"wifi-1", "app-2", "qr-3", "wifi-5", "dev-6", "np-7", "wifi-8", "at-9",
                "key-10", "1357", "url-12", "tok-13", "not-json-14", "plain-15"}) {
            for (String payload : scrubbed.values()) {
                assertFalse(secret, payload != null && payload.contains(secret));
            }
        }
        assertEquals(-1, dao.scrubOldRecords(10));

        // Run again from the start: nothing left to rewrite
        Map<Integer, String> versions = rowVersions();
        sql("UPDATE plugin_audit_scrub SET lastId = 0");
        scrubAll(3);
        assertEquals(scrubbed, payloads());
        assertEquals(versions, rowVersions());
    }

    @Test
    public void goesOnFromItsStoredProgress() throws Exception {
        reset();
        for (int i = 0; i < 5; i++) {
            insert("plugin.audit.action.update.user", head("PUT", "/rest/private/users")
                    + "\nBody: {\"password\":\"pw-" + i + "\"}" + UA);
        }
        sql("UPDATE plugin_audit_scrub SET lastId = 0, upToId = (SELECT MAX(id) FROM plugin_audit_log)");
        int first = Integer.parseInt(text("SELECT MIN(id) FROM plugin_audit_log"));

        assertEquals(2, dao.scrubOldRecords(2));                          // as if the server stopped here
        assertEquals(String.valueOf(first + 1), text("SELECT lastId FROM plugin_audit_scrub"));
        assertTrue(text("SELECT payload FROM plugin_audit_log WHERE id = " + (first + 2)).contains("pw-2"));

        scrubAll(2);
        assertEquals("0", text("SELECT COUNT(*) FROM plugin_audit_log WHERE payload LIKE '%pw-%'"));
    }

    @Test
    public void takesInWhatAServerOnTheOldReleaseWroteAfterTheUpgradeUntilDone() throws Exception {
        reset();
        insert("plugin.audit.action.update.user", head("PUT", "/rest/private/users") + "\nBody: {\"password\":\"pw-a\"}");
        sql("UPDATE plugin_audit_scrub SET lastId = 0, upToId = (SELECT MAX(id) FROM plugin_audit_log)");
        // Another node, not yet updated, wrote this after the changeset ran
        insert("plugin.audit.action.update.user", head("PUT", "/rest/private/users") + "\nBody: {\"password\":\"pw-b\"}");

        dao.extendScrubEnd();
        scrubAll(10);
        assertEquals("0", text("SELECT COUNT(*) FROM plugin_audit_log WHERE payload LIKE '%pw-%'"));

        // Once done, the end stays where it is: a later record is the new release's, masked when written
        String end = text("SELECT upToId FROM plugin_audit_scrub");
        insert("plugin.audit.action.update.user", head("PUT", "/rest/private/users") + "\nBody: {\"note\":\"n\"}");
        dao.extendScrubEnd();
        assertEquals(end, text("SELECT upToId FROM plugin_audit_scrub"));
        assertEquals(-1, dao.scrubOldRecords(10));
    }

    @Test
    public void neverReadsAHugeOldRecordWholeAndBoundsEachBatch() throws Exception {
        reset();
        // Old releases stored bodies of any size: 3 MB, and many just under the limit
        sql("INSERT INTO plugin_audit_log (customerId, action, payload) VALUES (1, 'plugin.audit.action.update.user', "
                + "'Method: PUT' || chr(10) || 'URI: /rest/private/users' || chr(10) || 'Body: {\"password\":\"huge-1\",\"pad\":\"' "
                + "|| repeat('x', 3 * 1024 * 1024) || '\"}' || chr(10) || 'User-Agent: Mozilla/5.0')");
        sql("INSERT INTO plugin_audit_log (customerId, action, payload) SELECT 1, 'plugin.audit.action.update.user', "
                + "'Method: PUT' || chr(10) || 'URI: /rest/private/users' || chr(10) || 'Body: {\"password\":\"big-' || g "
                + "|| '\",\"pad\":\"' || repeat('x', 1000 * 1000) || '\"}' || chr(10) || 'User-Agent: Mozilla/5.0' "
                + "FROM generate_series(1, 20) g");
        sql("UPDATE plugin_audit_scrub SET lastId = 0, upToId = (SELECT MAX(id) FROM plugin_audit_log)");
        int first = Integer.parseInt(text("SELECT MIN(id) FROM plugin_audit_log"));
        int upTo = Integer.parseInt(text("SELECT upToId FROM plugin_audit_scrub"));

        // The huge record comes back with no payload, only its ends; the batch stops at the byte budget
        java.util.List<com.hmdm.plugins.audit.persistence.domain.AuditScrubRow> batch = mapper.findForScrub(0, upTo,
                AuditScrubTaskModule.BATCH, com.hmdm.plugins.audit.rest.filter.StoredPayloads.READ_WHOLE,
                com.hmdm.plugins.audit.rest.filter.StoredPayloads.BATCH_BYTES);
        assertEquals(first, batch.get(0).getId());
        assertNull(batch.get(0).getPayload());
        assertEquals(4096, batch.get(0).getHead().length());
        assertTrue(batch.get(0).getTail().endsWith("\nUser-Agent: Mozilla/5.0"));
        assertTrue(String.valueOf(batch.size()), batch.size() > 10 && batch.size() < 21);
        for (int i = 1; i < batch.size(); i++) {
            assertTrue(batch.get(i).getPayload() != null && batch.get(i).getHead() == null);
        }

        dao.scrubOldRecords(AuditScrubTaskModule.BATCH);
        assertEquals("Method: PUT\nURI: /rest/private/users\nBody: (over 1 MB, not stored)\nUser-Agent: Mozilla/5.0",
                text("SELECT payload FROM plugin_audit_log WHERE id = " + first));
        // About 16 MB a batch: not all 21 records at once
        int progress = Integer.parseInt(text("SELECT lastId FROM plugin_audit_scrub"));
        assertTrue(String.valueOf(progress - first), progress - first < 20 && progress - first >= 10);

        scrubAll(AuditScrubTaskModule.BATCH);
        assertEquals("0", text("SELECT COUNT(*) FROM plugin_audit_log WHERE payload LIKE '%\"password\":\"big-%' "
                + "OR payload LIKE '%huge-1%'"));
    }

    @Test
    public void anOldBodyTheParserRefusesIsReplacedAndTheScrubGoesOn() throws Exception {
        // Old releases stored raw text: a lone surrogate escape, nesting past the parser's limit, broken JSON. Whatever
        // Jackson refuses (2.23 is stricter than 2.22), the scrub replaces the body and moves past the row.
        reset();
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            deep.append('[');
        }
        deep.append("\"deep-1\"");
        for (int i = 0; i < 3000; i++) {
            deep.append(']');
        }
        String[] bodies = {
                "{\"password\":\"pw-2\",\"note\":\"\\ud800\"}",
                "{\"a\":" + deep + ",\"password\":\"pw-3\"}",
                "{\"password\":\"pw-4\",\"note\":\"\\uZZZZ\"}",
                "{\"password\":\"pw-5\" \"x\":1}"};
        for (String body : bodies) {
            insert("plugin.audit.action.update.user", head("PUT", "/rest/private/users") + "\nBody: " + body + UA);
        }
        sql("UPDATE plugin_audit_scrub SET lastId = 0, upToId = (SELECT MAX(id) FROM plugin_audit_log)");

        scrubAll(2);

        assertEquals(text("SELECT upToId FROM plugin_audit_scrub"), text("SELECT lastId FROM plugin_audit_scrub"));
        assertEquals("0", text("SELECT COUNT(*) FROM plugin_audit_log WHERE payload LIKE '%pw-%' "
                + "OR payload LIKE '%deep-1%'"));
        assertEquals("every row kept, with its route and User-Agent", "4", text("SELECT COUNT(*) FROM plugin_audit_log "
                + "WHERE payload LIKE 'Method: PUT' || chr(10) || 'URI: /rest/private/users' || chr(10) || 'Body: %' "
                + "AND payload LIKE '%' || chr(10) || 'User-Agent: Mozilla/5.0'"));
    }

    @Test
    public void timesTheScrubOfManyRecords() throws Exception {
        String count = System.getenv("MDMESH_TEST_SCRUB_ROWS");
        Assume.assumeTrue("set MDMESH_TEST_SCRUB_ROWS to time the scrub", count != null && !count.isEmpty());
        reset();
        // A mix like a busy install's: configuration saves, account changes, sign-ins
        sql("INSERT INTO plugin_audit_log (customerId, action, payload) SELECT 1, "
                + "(ARRAY['plugin.audit.action.update.configuration','plugin.audit.action.update.user',"
                + "'plugin.audit.action.user.login'])[1 + g % 3], "
                + "'Method: PUT' || chr(10) || 'URI: /rest/private/x' || chr(10) || 'Body: {\"id\":' || g "
                + "|| ',\"name\":\"n' || g || '\",\"password\":\"p' || g || '\",\"wifiPassword\":\"w\","
                + "\"applicationSettings\":[{\"name\":\"a\",\"value\":\"v\"}],\"kioskMode\":true}' "
                + "|| chr(10) || 'User-Agent: Mozilla/5.0 (X11; Linux x86_64)' FROM generate_series(1, "
                + Integer.parseInt(count) + ") g");
        sql("UPDATE plugin_audit_scrub SET lastId = 0, upToId = (SELECT MAX(id) FROM plugin_audit_log)");
        long start = System.nanoTime();
        scrubAll(AuditScrubTaskModule.BATCH);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("AuditLogScrubTest: scrubbed " + count + " records in " + millis + " ms");
        assertEquals("0", text("SELECT COUNT(*) FROM plugin_audit_log WHERE payload LIKE '%\"password\":\"p%'"));
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private static void scrubAll(int batch) {
        while (dao.scrubOldRecords(batch) >= 0) {
            // next batch
        }
    }

    private static void reset() throws Exception {
        sql("TRUNCATE plugin_audit_log", "UPDATE plugin_audit_scrub SET lastId = 0, upToId = 0");
    }

    private static void insert(String action, String payload) throws Exception {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(
                "INSERT INTO plugin_audit_log (customerId, action, payload) VALUES (1, ?, ?)")) {
            p.setString(1, action);
            p.setString(2, payload);
            p.executeUpdate();
        }
    }

    private static Map<String, String> payloads() throws Exception {
        Map<String, String> payloads = new LinkedHashMap<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT action, payload FROM plugin_audit_log "
                     + "WHERE id <= (SELECT upToId FROM plugin_audit_scrub) ORDER BY id")) {
            while (r.next()) {
                payloads.put(r.getString(1), r.getString(2));
            }
        }
        return payloads;
    }

    /** Each row's xmin: a row an UPDATE wrote gets a new one. */
    private static Map<Integer, String> rowVersions() throws Exception {
        Map<Integer, String> versions = new LinkedHashMap<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT id, xmin::text FROM plugin_audit_log ORDER BY id")) {
            while (r.next()) {
                versions.put(r.getInt(1), r.getString(2));
            }
        }
        return versions;
    }

    private static String head(String method, String uri) {
        return "Method: " + method + "\nURI: " + uri;
    }

    private static void sql(String... statements) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    private static String text(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getString(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(url, user(), password());
    }

    private static ServletContext servletContext(Map<String, String> params) {
        return (ServletContext) Proxy.newProxyInstance(ServletContext.class.getClassLoader(),
                new Class<?>[]{ServletContext.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getInitParameterNames": return Collections.enumeration(params.keySet());
                        case "getInitParameter": return params.get((String) args[0]);
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static String user() {
        return USER == null ? "postgres" : USER;
    }

    private static String password() {
        return PASSWORD == null ? "" : PASSWORD;
    }
}
