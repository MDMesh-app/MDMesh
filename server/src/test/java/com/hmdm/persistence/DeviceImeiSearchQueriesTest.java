/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Real PostgreSQL and production MyBatis mapper: current telemetry IMEIs, legacy fields,
 * pagination/count agreement, and customer/group access boundaries. Uses an isolated schema.
 * Requires MDMESH_TEST_PG_URL; set MDMESH_TEST_PG_REQUIRED=true to forbid skipped runs. */
public class DeviceImeiSearchQueriesTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");

    private static String schema;
    private static Injector injector;

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
        try (Connection c = DriverManager.getConnection(url, user(), password())) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            new Liquibase("liquibase/db.changelog.xml", new ClassLoaderResourceAccessor(), db).update("common,private");
        }
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

    private static int device(int customerId, String number, String manual, String legacy, String telemetry) throws Exception {
        int config = (int) scalar("INSERT INTO " + schema + ".configurations (name,customerId) VALUES ('config-" + number + "'," + customerId + ") RETURNING id");
        int id = (int) scalar("INSERT INTO " + schema + ".devices (number,customerId,configurationId,imei,info) VALUES ('" + number
                + "'," + customerId + "," + config + "," + (manual == null ? "NULL" : "'" + manual + "'") + ","
                + (legacy == null ? "NULL" : "'{\"imei\":\"" + legacy + "\"}'") + ") RETURNING id");
        if (legacy != null) sql("UPDATE " + schema + ".devices SET infojson = info::json WHERE id = " + id);
        if (telemetry != null) sql("INSERT INTO " + schema + ".device_state (deviceNumber,updatedAt,telemetry) VALUES ('"
                + number + "',0,'" + telemetry + "')");
        return id;
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
        int d = device(c, "dual-" + c, null, null,
                "{\"identity\":{\"imei\":[\"359881234567890\",\"359889876543210\"]}}");
        device(c, "empty-" + c, null, null, "{\"identity\":{\"imei\":[]}}");
        device(c, "null-" + c, null, null, "{\"identity\":{\"imei\":null}}");
        device(c, "scalar-" + c, null, null, "{\"identity\":{\"imei\":\"unexpected\"}}");
        device(c, "missing-" + c, null, null, "{}");
        device(c, "no-state-" + c, null, null, null);
        matches(request(c,u,"359881234567890"), d);
        matches(request(c,u,"359889876543210"), d);
        matches(request(c,u,"35988"), d);
        matches(request(c,u,"765432"), d);
        matches(request(c,u,"000000000000000"));
    }

    @Test
    public void legacyAndManualImeisRemainSearchableAlongsideTelemetry() throws Exception {
        int c = customer(), u = user(c, true);
        int manual = device(c, "manual-" + c, "123451111111111", null, null);
        int legacy = device(c, "legacy-" + c, null, "123452222222222", null);
        int current = device(c, "current-" + c, null, null, "{\"identity\":{\"imei\":[\"123453333333333\"]}}");
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
        String telemetry = "{\"identity\":{\"imei\":[\"777771234567890\"]}}";
        int visible = device(c,"visible-" + c,null,null,telemetry);
        int hidden = device(c,"hidden-" + c,null,null,telemetry);
        device(other,"other-" + c,null,null,telemetry);
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
