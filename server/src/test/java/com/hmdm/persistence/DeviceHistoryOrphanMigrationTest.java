/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.persistence;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The 26.11.20-device-history-* changesets on an install that already has history rows of deleted devices: the
 * orphans go, every live device keeps its rows, and the foreign keys end up validated. The install is made by
 * migrating, then undoing the three changesets (their foreign keys dropped, their changelog rows deleted) so orphans
 * can be stored, then migrating again. The server serves nothing while Liquibase runs; the one overlap possible is a
 * transaction still open when the migration starts (another server instance), which the first changeset's locks wait
 * for, and which keeps its rows.
 *
 * <p>Runs when MDMESH_TEST_PG_URL is set (see AgentEnrollmentTransactionTest for the docker one-liner); with
 * MDMESH_TEST_PG_REQUIRED it fails without one. Works in its own schema, dropped afterwards.</p>
 */
public class DeviceHistoryOrphanMigrationTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");

    private static final String[] HISTORY = {"agentCommand", "device_state", "device_event", "device_location",
            "agentRolloutCanary"};

    private static String schema;
    private static String schemaUrl;

    @BeforeClass
    public static void setUpClass() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        schema = "orphan_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        sql("CREATE SCHEMA " + schema);
        schemaUrl = URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        migrate();
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (schema != null) sql("DROP SCHEMA " + schema + " CASCADE");
    }

    private static void migrate() throws Exception {
        try (Connection c = DriverManager.getConnection(schemaUrl, user(), password())) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            new Liquibase("liquibase/db.changelog.xml", new ClassLoaderResourceAccessor(), db).update("common,private");
        }
    }

    private static String user() {
        return USER == null ? "postgres" : USER;
    }

    private static String password() {
        return PASSWORD == null ? "" : PASSWORD;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(URL, user(), password());
    }

    private static void sql(String... statements) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String st : statements) s.execute(st);
        }
    }

    private static long scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getLong(1);
        }
    }

    /** A row in every history table for {@code number}, as statements (run on any connection). */
    private static String[] history(String number, int rolloutId) {
        return new String[]{
                "INSERT INTO " + schema + ".agentCommand (deviceNumber, type, status, createdAt) VALUES ('" + number
                        + "', 'device.lock', 'done', 0)",
                "INSERT INTO " + schema + ".device_state (deviceNumber, updatedAt) VALUES ('" + number + "', 0)",
                "INSERT INTO " + schema + ".device_event (deviceNumber, type, ts) VALUES ('" + number + "', 'boot', 0)",
                "INSERT INTO " + schema + ".device_location (deviceNumber, lat, lon, capturedAt, recordedAt) VALUES ('"
                        + number + "', 1, 2, 0, 0)",
                "INSERT INTO " + schema + ".agentRolloutCanary (rolloutId, deviceNumber) VALUES (" + rolloutId + ", '"
                        + number + "')"};
    }

    private static String insertDevice(String number, long customerId, long configurationId) {
        return "INSERT INTO " + schema + ".devices (number, customerId, configurationId, lastUpdate) VALUES ('" + number
                + "', " + customerId + ", " + configurationId + ", 0)";
    }

    private static void assertHistory(String number, long rowsEach) throws Exception {
        for (String table : HISTORY) {
            assertEquals(table + " rows of " + number, rowsEach,
                    scalar("SELECT count(*) FROM " + schema + "." + table + " WHERE deviceNumber = '" + number + "'"));
        }
    }

    @Test
    public void theMigrationDeletesOrphansOnlyAndValidatesTheKeys() throws Exception {
        String name = "c" + UUID.randomUUID().toString().substring(0, 8);
        long customerId = scalar("INSERT INTO " + schema + ".customers (name, filesDir, prefix) VALUES ('" + name
                + "', '" + name + "', '" + name + "') RETURNING id");
        long configurationId = scalar("INSERT INTO " + schema + ".configurations (name, customerId) VALUES ('" + name
                + "', " + customerId + ") RETURNING id");
        int rollout = (int) scalar("INSERT INTO " + schema + ".agentRollout (customerId, targetVersion, packageName, "
                + "apkUrl, stage, createdAt, updatedAt) VALUES (" + customerId + ", '1.0.0', 'p', 'u', 'cancelled', 0, 0) "
                + "RETURNING id");

        // The install as an older release left it: no keys, and rows of devices deleted since.
        for (String table : HISTORY) {
            sql("ALTER TABLE " + schema + "." + table + " DROP CONSTRAINT " + table + "_device_fk");
        }
        sql("DELETE FROM " + schema + ".databasechangelog WHERE id LIKE '26.11.20-device-history-%'");

        String live = "live-" + UUID.randomUUID();
        String gone = "gone-" + UUID.randomUUID();
        String enrolling = "enrolling-" + UUID.randomUUID();
        sql(insertDevice(live, customerId, configurationId));
        sql(history(live, rollout));
        sql(history(gone, rollout));
        sql("INSERT INTO " + schema + ".agentCommand (deviceNumber, type, status, createdAt) "
                + "VALUES (NULL, 'device.lock', 'pending', 0)");

        // A transaction still open when the migration begins (a device another instance is enrolling): adding the
        // keys waits for it.
        try (Connection enroll = connect(); Statement s = enroll.createStatement()) {
            enroll.setAutoCommit(false);
            s.execute(insertDevice(enrolling, customerId, configurationId));
            for (String st : history(enrolling, rollout)) s.execute(st);

            CompletableFuture<Void> migration = CompletableFuture.runAsync(() -> {
                try {
                    migrate();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(500); // the migration is waiting for this transaction's locks
            enroll.commit();
            migration.get(2, TimeUnit.MINUTES);
        }

        assertHistory(live, 1);
        assertHistory(enrolling, 1);
        assertHistory(gone, 0);
        assertEquals("agentCommand rows with no number", 0,
                scalar("SELECT count(*) FROM " + schema + ".agentCommand WHERE deviceNumber IS NULL"));
        assertEquals("validated history foreign keys", HISTORY.length,
                scalar("SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace "
                        + "WHERE n.nspname = '" + schema + "' AND c.contype = 'f' AND c.convalidated "
                        + "AND c.conname LIKE '%\\_device\\_fk'"));
    }
}
