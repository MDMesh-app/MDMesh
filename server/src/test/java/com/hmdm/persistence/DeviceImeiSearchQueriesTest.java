/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.persistence;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.hmdm.guice.module.PersistenceModule;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceSearchRequest;
import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.rest.json.DeviceLookupItem;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Real PostgreSQL and production MyBatis mapper: IMEIs reported by current agents (device_state.reportedImeis,
 * including what the update that added it fills from stored telemetry), legacy fields, ranking, pagination/count
 * agreement, and customer/group access boundaries. Uses an isolated schema.
 * Requires MDMESH_TEST_PG_URL; set MDMESH_TEST_PG_REQUIRED=true to forbid skipped runs. */
public class DeviceImeiSearchQueriesTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");

    private static final String CHANGESET = "26.11.23-deviceState-reportedImeis";

    private static String schema;
    private static Injector injector;
    /** Set up by {@link #reportedBeforeTheUpgrade}. */
    private static int upgradeCustomer, offline, messy;
    private static int[] unreadableIds;

    @BeforeClass
    public static void setUpClass() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        schema = "imei_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        sql("CREATE SCHEMA " + schema);
        String url = URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        migrate(url);
        reportedBeforeTheUpgrade(url);
        Map<String, String> jdbc = new HashMap<>();
        jdbc.put("JDBC.driver", "org.postgresql.Driver");
        jdbc.put("JDBC.url", url);
        jdbc.put("JDBC.username", user());
        jdbc.put("JDBC.password", password());
        injector = Guice.createInjector(new PersistenceModule(servletContext(jdbc)), new AbstractModule() {
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
    }

    private static void migrate(String url) throws Exception {
        try (Connection c = DriverManager.getConnection(url, user(), password())) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            new Liquibase("liquibase/db.changelog.xml", new ClassLoaderResourceAccessor(), db).update("common,private");
        }
    }

    /**
     * Devices whose agents reported before the update that added device_state.reportedImeis, and have not checked
     * in since: the schema goes back to before that changeset (its rollback), they are stored as such a server
     * stored them (telemetry only), and the changeset runs again.
     */
    private static void reportedBeforeTheUpgrade(String url) throws Exception {
        sql("ALTER TABLE " + schema + ".device_state DROP COLUMN reportedImeis",
                "DELETE FROM " + schema + ".databasechangelog WHERE id = '" + CHANGESET + "'");
        upgradeCustomer = customer();
        offline = device(upgradeCustomer, "offline-" + upgradeCustomer, null, null);
        stateBeforeUpgrade(offline, "{\"identity\":{\"imei\":[\"868681234567890\",\"868689876543210\"]},"
                + "\"dynamic\":{\"wifi\":{\"ssid\":\"lobby\"}}}");
        messy = device(upgradeCustomer, "messy-" + upgradeCustomer, null, null);
        stateBeforeUpgrade(messy, "{\"identity\":{\"imei\":[\"868680000000001\",42,null,\"\",\"with space\","
                + "\"123456789012345678901234567890123\",\"2\",\"3\",\"4\",\"5\"]}}");
        String[] unreadable = {
                "{\"identity\":{\"imei\":[\"868681234567890\"]},\"model\":\"\\u0000\"}",
                "{\"identity\":{\"imei\":[\"86868\\u0000\"]}}",
                "{\"identity\":{\"imei\":[\"868681234567890\"]},\"\\u0000\":true}",
                "{\"identity\":{\"imei\":[\"868681234567890\"]},\"model\":\"\\ud800\"}",
                "{\"identity\":{\"imei\":[\"868681234567890\"]},\"value\":1e1000000}",
                "{\"identity\":{\"imei\":[\"868681234567890\"]},\"deep\":" + repeat("[", 100000) + repeat("]", 100000) + "}",
                "{\"identity\":{\"imei\":", "\"imei\"", "[\"imei\"]",
                "{\"identity\":{\"imei\":\"868681234567890\"}}", "{\"identity\":{\"imei\":[]}}"
        };
        unreadableIds = new int[unreadable.length];
        for (int i = 0; i < unreadable.length; i++) {
            unreadableIds[i] = device(upgradeCustomer, "unreadable-" + i + "-" + upgradeCustomer, null, null);
            stateBeforeUpgrade(unreadableIds[i], unreadable[i]);
        }
        migrate(url);
    }

    private static String repeat(String s, int n) {
        return new String(new char[n]).replace("\0", s);
    }

    private static void stateBeforeUpgrade(int deviceId, String telemetry) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, user(), password()); PreparedStatement s = c.prepareStatement(
                "INSERT INTO " + schema + ".device_state (deviceNumber, updatedAt, telemetry) "
                        + "SELECT number, 0, ? FROM " + schema + ".devices WHERE id = ?")) {
            s.setString(1, telemetry);
            s.setInt(2, deviceId);
            assertEquals(1, s.executeUpdate());
        }
    }

    private static List<String> storedImeis(int deviceId) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT reportedImeis FROM " + schema + ".device_state ds JOIN " + schema
                     + ".devices d ON d.number = ds.deviceNumber WHERE d.id = " + deviceId)) {
            assertTrue(r.next());
            java.sql.Array imeis = r.getArray(1);
            return imeis == null ? null : Arrays.asList((String[]) imeis.getArray());
        }
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

    private static int customer() throws Exception {
        String name = "c" + UUID.randomUUID().toString().substring(0, 8);
        return (int) scalar("INSERT INTO " + schema + ".customers (name, filesDir, prefix) VALUES ('" + name + "', '"
                + name + "', '" + name + "') RETURNING id");
    }

    private static int user(int customerId, boolean allDevices) throws Exception {
        String login = "u" + UUID.randomUUID().toString().substring(0, 8);
        return (int) scalar("INSERT INTO " + schema + ".users (login,email,name,password,customerId,userRoleId,allDevicesAvailable) VALUES ('"
                + login + "','" + login + "@example.test','test','x'," + customerId + ",2," + allDevices + ") RETURNING id");
    }

    /** A device; {@code reported}: the IMEIs its agent reported, as the check-in stores them. */
    private static int device(int customerId, String number, String manual, String legacy, String... reported)
            throws Exception {
        int config = (int) scalar("INSERT INTO " + schema + ".configurations (name,customerId) VALUES ('config-" + number + "'," + customerId + ") RETURNING id");
        int id = (int) scalar("INSERT INTO " + schema + ".devices (number,customerId,configurationId,imei,info) VALUES ('" + number
                + "'," + customerId + "," + config + "," + (manual == null ? "NULL" : "'" + manual + "'") + ","
                + (legacy == null ? "NULL" : "'{\"imei\":\"" + legacy + "\"}'") + ") RETURNING id");
        if (legacy != null) sql("UPDATE " + schema + ".devices SET infojson = info::json WHERE id = " + id);
        if (reported.length > 0) {
            try (Connection c = DriverManager.getConnection(URL, user(), password()); PreparedStatement s = c.prepareStatement(
                    "INSERT INTO " + schema + ".device_state (deviceNumber,updatedAt,reportedImeis) VALUES (?,0,?)")) {
                s.setString(1, number);
                s.setArray(2, c.createArrayOf("text", reported));
                s.executeUpdate();
            }
        }
        return id;
    }

    /** The device as the device list returns it. */
    private static Device listed(int customer, int id) throws Exception {
        for (Device d : injector.getInstance(DeviceMapper.class).getAllDevices(request(customer, user(customer, true), null))) {
            if (d.getId() == id) return d;
        }
        throw new AssertionError("device " + id + " is not listed");
    }

    private static DeviceSearchRequest request(int customer, int user, String query) {
        DeviceSearchRequest r = new DeviceSearchRequest();
        r.setCustomerId(customer); r.setUserId(user); r.setValue(query);
        return r;
    }

    private static void matches(DeviceSearchRequest r, int... expected) {
        DeviceMapper mapper = injector.getInstance(DeviceMapper.class);
        List<Integer> actual = new ArrayList<>();
        for (Device d : mapper.getAllDevices(r)) actual.add(d.getId());
        Collections.sort(actual);
        List<Integer> wanted = new ArrayList<>();
        for (int id : expected) wanted.add(id);
        Collections.sort(wanted);
        assertEquals(wanted, actual);
        assertEquals((long) expected.length, (long) mapper.countAllDevices(r));
    }

    @Test
    public void fullAndPartialImeisMatchEitherSimWithoutDuplicatingTheDevice() throws Exception {
        int c = customer(), u = user(c, true);
        int d = device(c, "dual-" + c, null, null, "359881234567890", "359889876543210");
        device(c, "none-" + c, null, null);
        matches(request(c,u,"359881234567890"), d);
        matches(request(c,u,"359889876543210"), d);
        matches(request(c,u,"35988"), d);
        matches(request(c,u,"765432"), d);
        matches(request(c,u,"000000000000000"));
    }

    @Test
    public void aDeviceThatReportedBeforeTheUpgradeAndHasBeenOfflineSinceIsFoundByItsImeis() throws Exception {
        int u = user(upgradeCustomer, true);
        matches(request(upgradeCustomer,u,"868681234567890"), offline);
        matches(request(upgradeCustomer,u,"868689876543210"), offline);
        matches(request(upgradeCustomer,u,"876543"), offline);
        assertEquals(Arrays.asList("868681234567890", "868689876543210"), storedImeis(offline));
        // The rules the check-in applies (AgentCheckInLimitsTest): the first four strings of 1-32 printable characters.
        assertEquals(Arrays.asList("868680000000001", "2", "3", "4"), storedImeis(messy));
    }

    @Test
    public void telemetryTheUpgradeCannotReadLeavesThoseDevicesWithoutImeisButSearchable() throws Exception {
        int u = user(upgradeCustomer, true);
        for (int id : unreadableIds) assertNull(storedImeis(id));
        matches(request(upgradeCustomer,u,"86868"), offline, messy);
        matches(request(upgradeCustomer,u,"unreadable-"), unreadableIds);
    }

    @Test
    public void aReportedImeiMatchRanksLikeAnImeiSetByTheAdministrator() throws Exception {
        int c = customer(), u = user(c, true);
        // A description holding the digits is a weaker match (2) than an IMEI (8), whichever agent reported it.
        int described = device(c, "a-described-" + c, null, null);
        sql("UPDATE " + schema + ".devices SET description = 'spare for 359881234567890' WHERE id = " + described);
        int reported = device(c, "b-reported-" + c, null, null, "359881234567890");
        DeviceMapper mapper = injector.getInstance(DeviceMapper.class);
        List<Integer> order = new ArrayList<>();
        for (Device d : mapper.getAllDevices(request(c,u,"359881234567890"))) order.add(d.getId());
        assertEquals(Arrays.asList(reported, described), order);
        DeviceSearchRequest firstPage = request(c,u,"359881234567890");
        firstPage.setPageSize(1);
        assertEquals(reported, (int) mapper.getAllDevices(firstPage).get(0).getId());
    }

    @Test
    public void deviceLookupFindsReportedImeisWithinTheUsersGroups() throws Exception {
        int c = customer(), other = customer(), admin = user(c, true), restricted = user(c, false);
        int visible = device(c, "visible-" + c, null, null, "555551234567890");
        int hidden = device(c, "hidden-" + c, null, null, "555551234567890");
        device(other, "other-" + c, null, null, "555551234567890");
        int group = (int) scalar("INSERT INTO " + schema + ".groups (name,customerId) VALUES ('allowed'," + c + ") RETURNING id");
        sql("INSERT INTO " + schema + ".deviceGroups (deviceId,groupId) VALUES (" + visible + "," + group + ")",
                "INSERT INTO " + schema + ".userDeviceGroupsAccess (userId,groupId) VALUES (" + restricted + "," + group + ")");
        DeviceMapper mapper = injector.getInstance(DeviceMapper.class);
        List<Integer> found = new ArrayList<>();
        for (DeviceLookupItem d : mapper.lookupDevices(admin, c, "%12345%", 10)) found.add(d.getId());
        Collections.sort(found);
        assertEquals(Arrays.asList(visible, hidden), found);
        found.clear();
        for (DeviceLookupItem d : mapper.lookupDevices(restricted, c, "%555551234567890%", 10)) found.add(d.getId());
        assertEquals(Collections.singletonList(visible), found);
    }

    @Test
    public void theDeviceListCarriesTheReportedImeis() throws Exception {
        assertEquals(Arrays.asList("868681234567890", "868689876543210"),
                Arrays.asList(listed(upgradeCustomer, offline).getReportedImeis()));
        assertNull(listed(upgradeCustomer, unreadableIds[0]).getReportedImeis());
    }

    @Test
    public void legacyAndManualImeisRemainSearchableAlongsideTelemetry() throws Exception {
        int c = customer(), u = user(c, true);
        int manual = device(c, "manual-" + c, "123451111111111", null);
        int legacy = device(c, "legacy-" + c, null, "123452222222222");
        int current = device(c, "current-" + c, null, null, "123453333333333");
        matches(request(c,u,"12345"), manual, legacy, current);
        DeviceSearchRequest r = request(c,u,"12345");
        r.setPageSize(1); r.setPageNum(2);
        DeviceMapper mapper = injector.getInstance(DeviceMapper.class);
        assertEquals(1, mapper.getAllDevices(r).size());
        assertEquals(3L, (long) mapper.countAllDevices(r));
    }

    @Test
    public void telemetrySearchPreservesCustomerAndGroupAccessAndFastNumberSearch() throws Exception {
        int c = customer(), other = customer(), admin = user(c,true), restricted = user(c,false);
        String imei = "777771234567890";
        int visible = device(c,"visible-" + c,null,null,imei);
        int hidden = device(c,"hidden-" + c,null,null,imei);
        device(other,"other-" + c,null,null,imei);
        int group = (int) scalar("INSERT INTO " + schema + ".groups (name,customerId) VALUES ('allowed'," + c + ") RETURNING id");
        sql("INSERT INTO " + schema + ".deviceGroups (deviceId,groupId) VALUES (" + visible + "," + group + ")",
                "INSERT INTO " + schema + ".userDeviceGroupsAccess (userId,groupId) VALUES (" + restricted + "," + group + ")");
        matches(request(c,admin,"77777"), visible, hidden);
        matches(request(c,restricted,"77777"), visible);
        DeviceSearchRequest r = request(c,admin,"777771234567890");
        r.setFastSearch(true);
        matches(r);
        r.setValue("visible-" + c);
        matches(r, visible);
    }
}
