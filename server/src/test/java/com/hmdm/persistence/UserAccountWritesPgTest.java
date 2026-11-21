/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.hmdm.guice.module.PersistenceModule;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.security.SecurityContext;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.After;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Account writes on real PostgreSQL, with a second connection standing in for another admin:
 * <ul>
 *     <li>a name and email change (/details, a customer's admin) never writes back the role and access it read before
 *     another admin's demotion committed;</li>
 *     <li>a write waits for the row lock another admin's change holds, then checks the account as that change left it
 *     (SELECT ... FOR UPDATE, then the re-check);</li>
 *     <li>shared mode: an access row naming another customer's group or configuration reaches nothing.</li>
 * </ul>
 *
 * <p>Runs when MDMESH_TEST_PG_URL is set (see AgentEnrollmentTransactionTest); with MDMESH_TEST_PG_REQUIRED it fails
 * without one. Works in its own schema, dropped afterwards.</p>
 */
public class UserAccountWritesPgTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");
    private static final int ORG_ADMIN = 2;
    private static final int PLAIN_USER = 3;

    private static String schema;
    private static String url;
    private static Injector injector;

    @BeforeClass
    public static void setUpClass() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        schema = "acct_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        url = URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try (Connection c = connect()) {
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
                props.setProperty("role.orgadmin.id", String.valueOf(ORG_ADMIN));
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

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (schema != null) {
            try (Connection c = DriverManager.getConnection(URL, user(), password());
                 Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void aNameChangeKeepsADemotionCommittedAfterTheAccountWasRead() throws Exception {
        int customer = customer();
        int x = user(customer, ORG_ADMIN, true);
        UserMapper users = injector.getInstance(UserMapper.class);
        User read = users.findById(x);                       // what /details read, before the demotion
        SecurityContext.init(read);

        sql("UPDATE users SET userRoleId = " + PLAIN_USER + ", allDevicesAvailable = false, "
                + "allConfigAvailable = false WHERE id = " + x);
        injector.getInstance(UserDAO.class).updateUserIdentity(x, null, "Renamed", read.getEmail());

        assertEquals("Renamed|" + PLAIN_USER + "|false|false",
                text("SELECT name || '|' || userRoleId || '|' || allDevicesAvailable || '|' || allConfigAvailable "
                        + "FROM users WHERE id = " + x));
    }

    @Test
    public void aWriteWaitsForAnotherAdminsChangeAndChecksTheAccountItLeft() throws Exception {
        int customer = customer();
        int lobby = group(customer, "lobby");
        int admin = user(customer, ORG_ADMIN, false);
        int target = user(customer, PLAIN_USER, false);
        sql("INSERT INTO userDeviceGroupsAccess (userId, groupId) VALUES (" + admin + ", " + lobby + "), ("
                + target + ", " + lobby + ")");
        User session = injector.getInstance(UserMapper.class).findById(admin);

        try (Connection other = connect()) {
            // Another admin widens the account, and holds its row until it commits
            other.setAutoCommit(false);
            try (Statement s = other.createStatement()) {
                s.execute("SELECT id FROM users WHERE id = " + target + " FOR UPDATE");
                s.execute("UPDATE users SET allDevicesAvailable = true WHERE id = " + target);
            }
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread write = new Thread(() -> {
                SecurityContext.init(session);
                try {
                    injector.getInstance(UserDAO.class).updateUserIdentity(target, null, "renamed",
                            "t" + target + "@example.test");
                    outcome.set(new AssertionError("written"));
                } catch (Throwable t) {
                    outcome.set(t);
                } finally {
                    SecurityContext.release();
                }
            });
            write.start();
            write.join(1500);
            assertTrue("the write must wait for the other admin's lock", write.isAlive());
            other.commit();
            write.join(15000);
            assertFalse(write.isAlive());
            assertTrue(String.valueOf(outcome.get()), outcome.get() instanceof com.hmdm.security.SecurityException);
        }
        assertFalse("renamed".equals(text("SELECT name FROM users WHERE id = " + target)));
    }

    @Test
    public void anAccessRowNamingAnotherCustomersGroupOrConfigurationReachesNothing() throws Exception {
        int mine = customer();
        int theirs = customer();
        int ownGroup = group(mine, "own");
        int foreignGroup = group(theirs, "foreign");
        int ownConfiguration = configuration(mine, "own");
        int foreignConfiguration = configuration(theirs, "foreign");
        int u = user(mine, PLAIN_USER, false);
        sql("UPDATE users SET allConfigAvailable = false WHERE id = " + u,
                "INSERT INTO userDeviceGroupsAccess (userId, groupId) VALUES (" + u + ", " + ownGroup + "), (" + u
                        + ", " + foreignGroup + ")",
                "INSERT INTO userConfigurationAccess (userId, configurationId) VALUES (" + u + ", " + ownConfiguration
                        + "), (" + u + ", " + foreignConfiguration + ")");

        User read = injector.getInstance(UserMapper.class).findById(u);

        assertEquals(Collections.singletonList(ownGroup),
                read.getGroups().stream().map(LookupItem::getId).collect(Collectors.toList()));
        assertEquals(Collections.singletonList(ownConfiguration),
                read.getConfigurations().stream().map(LookupItem::getId).collect(Collectors.toList()));
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private static int customer() throws Exception {
        String name = "c" + UUID.randomUUID().toString().substring(0, 8);
        return scalar("INSERT INTO customers (name, filesDir, prefix) VALUES ('" + name + "', '" + name + "', '"
                + name + "') RETURNING id");
    }

    private static int group(int customerId, String name) throws Exception {
        return scalar("INSERT INTO groups (name, customerId) VALUES ('" + name + "', " + customerId + ") RETURNING id");
    }

    private static int configuration(int customerId, String name) throws Exception {
        return scalar("INSERT INTO configurations (name, customerId) VALUES ('" + name + "', " + customerId
                + ") RETURNING id");
    }

    private static int user(int customerId, int roleId, boolean allAccess) throws Exception {
        String login = "u" + UUID.randomUUID().toString().substring(0, 8);
        return scalar("INSERT INTO users (login, email, name, password, customerId, userRoleId, allDevicesAvailable, "
                + "allConfigAvailable) VALUES ('" + login + "', '" + login + "@example.test', '" + login + "', 'x', "
                + customerId + ", " + roleId + ", " + allAccess + ", true) RETURNING id");
    }

    private static void sql(String... statements) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    private static int scalar(String query) throws Exception {
        return Integer.parseInt(text(query));
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
