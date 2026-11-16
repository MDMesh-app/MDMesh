/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.hmdm.guice.module.PersistenceModule;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.mapper.ConfigurationMapper;
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

/**
 * The kiosk background image queries on the real schema (the server's Liquibase changelog) through the real mapper:
 * the account's managed images by their bytes (Enter kiosk's current URL, KioskCommandImages), and the image uses a
 * user may open (the gallery and describe, in one query instead of an access check per configuration).
 *
 * <p>Runs when MDMESH_TEST_PG_URL is set (see AgentEnrollmentTransactionTest); with MDMESH_TEST_PG_REQUIRED it fails
 * without one. Works in its own schema, dropped afterwards.</p>
 */
public class KioskBackgroundImageQueriesTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static String schema;
    private static Injector injector;

    @BeforeClass
    public static void setUpClass() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        schema = "kbg_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private static int file(int customerId, String path) throws Exception {
        return (int) scalar("INSERT INTO " + schema + ".uploadedFiles (customerId, filePath) VALUES (" + customerId
                + ", '" + path + "') RETURNING id");
    }

    private static int configuration(int customerId, String name, Integer fileId, String url, String sha) throws Exception {
        return (int) scalar("INSERT INTO " + schema + ".configurations (name, customerId, backgroundImageFileId, "
                + "backgroundImageUrl, backgroundImageSha256) VALUES ('" + name + "', " + customerId + ", "
                + fileId + ", " + (url == null ? "NULL" : "'" + url + "'") + ", " + (sha == null ? "NULL" : "'" + sha + "'")
                + ") RETURNING id");
    }

    private static int user(int customerId, boolean allConfigurations) throws Exception {
        String login = "u" + UUID.randomUUID().toString().substring(0, 8);
        return (int) scalar("INSERT INTO " + schema + ".users (login, email, name, password, customerId, userRoleId, "
                + "allConfigAvailable) VALUES ('" + login + "', '" + login + "@example.test', '" + login + "', 'x', "
                + customerId + ", 2, " + allConfigurations + ") RETURNING id");
    }

    private static List<Integer> ids(List<Configuration> rows) {
        List<Integer> out = new ArrayList<>();
        for (Configuration c : rows) out.add(c.getId());
        Collections.sort(out);
        return out;
    }

    @Test
    public void managedImagesByTheirBytesAreTheAccountsOwnWithAFile() throws Exception {
        int acme = customer();
        int other = customer();
        int f = file(acme, "kiosk-0123456789ab-beach.jpg");
        int g = file(other, "kiosk-0123456789ab-beach.jpg");
        int lobby = configuration(acme, "lobby", f, "http://old.example/files/acme/kiosk-0123456789ab-beach.jpg", SHA);
        int hall = configuration(acme, "hall", f, "http://old.example/files/acme/kiosk-0123456789ab-beach.jpg", SHA);
        configuration(acme, "legacy", null, "https://cdn.example/x.jpg", SHA);   // a digest without a file: not managed
        configuration(acme, "other image", f, "http://old.example/files/acme/y.jpg", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        configuration(other, "theirs", g, "http://old.example/files/other/kiosk-0123456789ab-beach.jpg", SHA);

        ConfigurationMapper mapper = injector.getInstance(ConfigurationMapper.class);
        List<Configuration> rows = mapper.getManagedBackgroundImageConfigurations(acme, SHA);
        assertEquals(java.util.Arrays.asList(Math.min(lobby, hall), Math.max(lobby, hall)), ids(rows));
        Configuration first = rows.get(0);
        assertEquals(acme, first.getCustomerId());
        assertEquals(Integer.valueOf(f), first.getBackgroundImageFileId());
        assertEquals(SHA, first.getBackgroundImageSha256());
        assertEquals("http://old.example/files/acme/kiosk-0123456789ab-beach.jpg", first.getBackgroundImageUrl());
    }

    @Test
    public void theImageUsesAUserMayOpenFollowTheirConfigurationAccess() throws Exception {
        int acme = customer();
        int f = file(acme, "kiosk-aaaaaaaaaaaa-a.png");
        int open = configuration(acme, "open", f, null, SHA);
        int closed = configuration(acme, "closed", f, null, SHA);
        configuration(acme, "no image", null, null, null);
        int limited = user(acme, false);
        sql("INSERT INTO " + schema + ".userConfigurationAccess (userId, configurationId) VALUES (" + limited + ", " + open + ")");
        int everything = user(acme, true);

        ConfigurationMapper mapper = injector.getInstance(ConfigurationMapper.class);
        assertEquals(Collections.singletonList(open), ids(mapper.getAccessibleBackgroundImageUses(acme, limited)));
        assertEquals(java.util.Arrays.asList(open, closed), ids(mapper.getAccessibleBackgroundImageUses(acme, everything)));
        assertEquals(Integer.valueOf(f), mapper.getAccessibleBackgroundImageUses(acme, limited).get(0).getBackgroundImageFileId());
    }
}
