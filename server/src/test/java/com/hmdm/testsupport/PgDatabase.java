/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.testsupport;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.hmdm.guice.module.PersistenceModule;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.Assume;
import org.junit.rules.ExternalResource;

import javax.servlet.ServletContext;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.fail;

/**
 * A class rule: the server's schema and persistence on real PostgreSQL, in a schema of its own, dropped afterwards.
 *
 * <p>Runs when MDMESH_TEST_PG_URL is set (see AgentEnrollmentTransactionTest); with MDMESH_TEST_PG_REQUIRED it fails
 * without one.</p>
 */
public class PgDatabase extends ExternalResource {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");
    public static final int ORG_ADMIN = 2;

    private final String prefix;
    private String schema;
    private String url;
    private Injector injector;

    public PgDatabase(String schemaPrefix) {
        this.prefix = schemaPrefix;
    }

    @Override
    protected void before() throws Throwable {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        String name = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + name);
        }
        schema = name;
        url = URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        // ExternalResource doesn't run after() when before() fails.
        try {
            migrate();
        } catch (Throwable t) {
            after();
            throw t;
        }
    }

    private void migrate() throws Exception {
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

    @Override
    protected void after() {
        if (schema == null) return;
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA " + schema + " CASCADE");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public <T> T get(Class<T> type) {
        return injector.getInstance(type);
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user(), password());
    }

    public void sql(String... statements) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    public int scalar(String query) throws SQLException {
        return Integer.parseInt(text(query));
    }

    public String text(String query) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getString(1);
        }
    }

    public int customer() throws SQLException {
        String name = "c" + UUID.randomUUID().toString().substring(0, 8);
        return scalar("INSERT INTO customers (name, filesDir, prefix) VALUES ('" + name + "', '" + name + "', '"
                + name + "') RETURNING id");
    }

    public int configuration(int customerId, String name) throws SQLException {
        return scalar("INSERT INTO configurations (name, customerId) VALUES ('" + name + "', " + customerId
                + ") RETURNING id");
    }

    /** A user who may use every configuration, and every device or none. */
    public int user(int customerId, int roleId, boolean allDevices) throws SQLException {
        String login = "u" + UUID.randomUUID().toString().substring(0, 8);
        return scalar("INSERT INTO users (login, email, name, password, customerId, userRoleId, allDevicesAvailable, "
                + "allConfigAvailable) VALUES ('" + login + "', '" + login + "@example.test', '" + login + "', 'x', "
                + customerId + ", " + roleId + ", " + allDevices + ", true) RETURNING id");
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
