/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.persistence;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.hmdm.guice.module.PersistenceModule;
import com.hmdm.rest.json.DeviceCreateOptions;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The Headwind launcher API's rule (UnsecureDAO.findLegacyDevice, createLegacyDevice, getLegacyConfigurationByQRCodeKey)
 * on the real schema and mappers: an agent device (one with a check-in secret) is refused however it is named, a
 * configuration it uses is neither served nor created into, and launcher devices elsewhere are served.
 *
 * <p>Runs when MDMESH_TEST_PG_URL is set (see AgentEnrollmentTransactionTest for the docker one-liner); with
 * MDMESH_TEST_PG_REQUIRED it fails without one. Works in its own schema, dropped afterwards.</p>
 */
public class LegacyDeviceSqlTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");

    private static String schema;
    private static UnsecureDAO dao;
    private static int launcherConfig;
    private static int agentConfig;

    @BeforeClass
    public static void setUpClass() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        schema = "legacy_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        sql("CREATE SCHEMA " + schema);
        String url = URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try (Connection c = DriverManager.getConnection(url, user(), password())) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            new Liquibase("liquibase/db.changelog.xml", new ClassLoaderResourceAccessor(), db).update("common,private");
        }
        Map<String, String> jdbc = new HashMap<>();
        jdbc.put("JDBC.driver", "org.postgresql.Driver");
        jdbc.put("JDBC.url", url);
        jdbc.put("JDBC.username", user());
        jdbc.put("JDBC.password", password());
        Injector injector = Guice.createInjector(new PersistenceModule(servletContext(jdbc)), new AbstractModule() {
            @Override
            protected void configure() {
                Properties props = new Properties();
                props.setProperty("files.directory", "/nonexistent-mdmesh-test-files");
                props.setProperty("role.orgadmin.id", "2");
                props.setProperty("launcher.package", "com.hmdm.launcher");
                props.setProperty("device.fast.search.chars", "5");
                props.setProperty("aapt.command", "aapt");
                props.setProperty("apk.trusted.url", "");
                props.setProperty("base.url", "https://mdm.example.test");
                props.setProperty("hash.secret", "test-hash-secret");
                Names.bindProperties(binder(), props);
            }
        });
        dao = injector.getInstance(UnsecureDAO.class);

        // The first customer (the seed's): a launcher configuration, and an agent configuration an agent device uses.
        launcherConfig = (int) scalar("INSERT INTO " + schema + ".configurations (name, customerId, qrCodeKey) "
                + "VALUES ('launcher', 1, 'launcher-key') RETURNING id");
        agentConfig = (int) scalar("INSERT INTO " + schema + ".configurations (name, customerId, qrCodeKey) "
                + "VALUES ('agent', 1, 'agent-key') RETURNING id");
        sql("INSERT INTO " + schema + ".devices (number, oldNumber, imei, infojson, customerId, configurationId, "
                        + "lastUpdate, agentSecretHash) VALUES ('agent-1', 'agent-1-old', 'agent-1-imei', "
                        + "'{\"serial\": \"agent-1-serial\"}', 1, " + agentConfig + ", 0, 'secret-hash')",
                "INSERT INTO " + schema + ".devices (number, customerId, configurationId, lastUpdate) VALUES "
                        + "('launcher-1', 1, " + launcherConfig + ", 0), "
                        + "('launcher-in-agent-config', 1, " + agentConfig + ", 0)");
    }

    /** A servlet context that only answers init parameters, as the server's context.xml provides them. */
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

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (schema != null) sql("DROP SCHEMA " + schema + " CASCADE");
    }

    private static String user() {
        return USER == null ? "postgres" : USER;
    }

    private static String password() {
        return PASSWORD == null ? "" : PASSWORD;
    }

    private static void sql(String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement()) {
            for (String st : statements) s.execute(st);
        }
    }

    private static long scalar(String query) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getLong(1);
        }
    }

    private static LegacyDevice.Status find(String identifier) {
        return dao.findLegacyDevice(identifier, LegacyDevice.Lookup.IMEI_OR_SERIAL).getStatus();
    }

    @Test
    public void anAgentDeviceIsRefusedHoweverItIsNamed() {
        for (String name : new String[]{"agent-1", "agent-1-old", "agent-1-imei", "agent-1-serial"}) {
            assertEquals(name, LegacyDevice.Status.AGENT_DEVICE, find(name));
        }
    }

    @Test
    public void onlyLauncherDevicesOutsideAgentConfigurationsAreServed() {
        assertEquals(LegacyDevice.Status.SERVED, find("launcher-1"));
        assertEquals(LegacyDevice.Status.AGENT_CONFIGURATION, find("launcher-in-agent-config"));
        assertEquals(LegacyDevice.Status.ABSENT, find("nobody"));
    }

    private static int configuration(String name) throws Exception {
        return (int) scalar("INSERT INTO " + schema + ".configurations (name, customerId) VALUES ('" + name
                + "', 1) RETURNING id");
    }

    /** An enrollment token of {@code customerId}, bound to {@code configurationId} (null: the customer's default). */
    private static void token(int customerId, Integer configurationId, String state) throws Exception {
        long now = System.currentTimeMillis();
        sql("INSERT INTO " + schema + ".agentEnrollmentToken (token, customerId, used, createdAt, expiresAt, "
                + "configurationId, maxUses, useCount, revokedAt) VALUES ('" + UUID.randomUUID() + "', " + customerId
                + ", " + state.equals("used up") + ", 0, " + (state.equals("expired") ? now - 1000 : now + 3_600_000L)
                + ", " + configurationId + ", 1, " + (state.equals("used up") ? 1 : 0) + ", "
                + (state.equals("stopped") ? String.valueOf(now) : "NULL") + ")");
    }

    @Test
    public void aConfigurationALiveEnrollmentTokenEnrollsIntoIsAgentManaged() throws Exception {
        int bound = configuration("token-bound");
        token(1, bound, "live");
        sql("INSERT INTO " + schema + ".devices (number, customerId, configurationId, lastUpdate) VALUES "
                + "('launcher-in-token-config', 1, " + bound + ", 0)");
        assertEquals(LegacyDevice.Status.AGENT_CONFIGURATION, find("launcher-in-token-config"));

        // Tokens that can't enroll any more don't count.
        for (String state : new String[]{"expired", "used up", "stopped"}) {
            int c = configuration("token-" + state);
            token(1, c, state);
            sql("INSERT INTO " + schema + ".devices (number, customerId, configurationId, lastUpdate) VALUES "
                    + "('launcher-" + state.replace(' ', '-') + "', 1, " + c + ", 0)");
            assertEquals(state, LegacyDevice.Status.SERVED, find("launcher-" + state.replace(' ', '-')));
        }
    }

    @Test
    public void theDefaultConfigurationCountsWhileALiveTokenOfItsCustomerHasNone() throws Exception {
        int defaultConfig = configuration("default");
        sql("INSERT INTO " + schema + ".settings (customerId, createNewDevices, newDeviceConfigurationId) VALUES (1, "
                + "true, " + defaultConfig + ") ON CONFLICT (customerId) DO UPDATE SET createNewDevices = true, "
                + "newDeviceConfigurationId = " + defaultConfig);
        // Another customer's token (by id only: a second customer row would make the install multi-tenant, where
        // creation without options is off anyway).
        token(999, null, "live");
        token(1, null, "expired");
        assertEquals("only other customers' or dead tokens", LegacyDevice.Status.SERVED,
                dao.createLegacyDevice("before-token").getStatus());

        token(1, null, "live");
        assertEquals(LegacyDevice.Status.AGENT_CONFIGURATION, dao.createLegacyDevice("after-token").getStatus());
        assertEquals(0, scalar("SELECT count(*) FROM " + schema + ".devices WHERE number = 'after-token'"));
        assertEquals(LegacyDevice.Status.AGENT_CONFIGURATION, find("before-token"));
    }

    @Test
    public void anAgentConfigurationIsNeitherServedByQrKeyNorCreatedInto() throws Exception {
        assertNull(dao.getLegacyConfigurationByQRCodeKey("agent-key"));
        assertEquals(launcherConfig, (int) dao.getLegacyConfigurationByQRCodeKey("launcher-key").getId());

        DeviceCreateOptions options = new DeviceCreateOptions();
        options.setConfiguration("agent-key");
        assertEquals(LegacyDevice.Status.AGENT_CONFIGURATION, dao.createLegacyDevice("new-agent", options).getStatus());
        assertEquals(0, scalar("SELECT count(*) FROM " + schema + ".devices WHERE number = 'new-agent'"));

        options.setConfiguration("launcher-key");
        LegacyDevice created = dao.createLegacyDevice("new-launcher", options);
        assertTrue(created.isServed());
        assertEquals(launcherConfig, (int) created.getDevice().getConfigurationId());
    }
}
