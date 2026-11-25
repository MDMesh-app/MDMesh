/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import com.hmdm.persistence.mapper.CommonMapper;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * The 26.11.23-settings-twoFactor-drop changeset on an install whose settings still say twoFactor = true (a database
 * carried over from Headwind, or a hand edit): the column goes, this release reads such a row before the changeset
 * runs, and an older release put back on the migrated database without a restore (--allow-downgrade) reads false. The
 * install is made by migrating, then putting the column back with its changelog row deleted, then migrating again.
 *
 * <p>Runs when MDMESH_TEST_PG_URL is set (see AgentEnrollmentTransactionTest for the docker one-liner); with
 * MDMESH_TEST_PG_REQUIRED it fails without one. Works in its own schema, dropped afterwards.</p>
 */
public class SettingsTwoFactorDropMigrationTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");
    private static final String CHANGESET = "26.11.23-settings-twoFactor-drop";

    private static String schema;
    private static String schemaUrl;
    private static SqlSessionFactory mybatis;

    /** settings as 0.5.x and older map it: SELECT * into a class that still has the field. */
    public static class OlderSettings {
        private int customerId;
        private boolean twoFactor;   // false unless a column sets it, as in 0.5.x

        public int getCustomerId() {
            return customerId;
        }

        public void setCustomerId(int customerId) {
            this.customerId = customerId;
        }

        public boolean isTwoFactor() {
            return twoFactor;
        }

        public void setTwoFactor(boolean twoFactor) {
            this.twoFactor = twoFactor;
        }
    }

    public interface OlderReleaseMapper {
        @Select("SELECT * FROM settings WHERE customerId = #{customerId} LIMIT 1")
        OlderSettings getSettings(@Param("customerId") int customerId);
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        schema = "twofactor_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        sql("CREATE SCHEMA " + schema);
        schemaUrl = URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        migrate();

        // MyBatis's defaults, as the server's PersistenceModule leaves them
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.postgresql.Driver", schemaUrl, user(), password())));
        config.addMapper(CommonMapper.class);
        config.addMapper(OlderReleaseMapper.class);
        mybatis = new SqlSessionFactoryBuilder().build(config);
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

    private static long column() throws Exception {
        return scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + schema
                + "' AND table_name = 'settings' AND column_name = 'twofactor'");
    }

    @Test
    public void theColumnGoesAndNoReleaseReadsTrueAfterwards() throws Exception {
        String name = "c" + UUID.randomUUID().toString().substring(0, 8);
        int customer = (int) scalar("INSERT INTO " + schema + ".customers (name, filesDir, prefix) VALUES ('" + name
                + "', '" + name + "', '" + name + "') RETURNING id");
        sql("INSERT INTO " + schema + ".settings (customerId) VALUES (" + customer + ")");

        // The install as an older release left it: the column, true for this customer.
        sql("ALTER TABLE " + schema + ".settings ADD COLUMN IF NOT EXISTS twoFactor BOOLEAN NOT NULL DEFAULT false",
                "UPDATE " + schema + ".settings SET twoFactor = true WHERE customerId = " + customer,
                "DELETE FROM " + schema + ".databasechangelog WHERE id = '" + CHANGESET + "'");
        try (SqlSession s = mybatis.openSession()) {
            // This release's code on that row (the changeset runs before the server serves): read, column ignored
            assertEquals(customer, s.getMapper(CommonMapper.class).getSettings(customer).getCustomerId());
        }

        migrate();

        assertEquals("settings.twoFactor is dropped", 0, column());
        try (SqlSession s = mybatis.openSession()) {
            assertEquals(customer, s.getMapper(CommonMapper.class).getSettings(customer).getCustomerId());
            OlderSettings older = s.getMapper(OlderReleaseMapper.class).getSettings(customer);
            assertEquals(customer, older.getCustomerId());
            assertFalse("an older release on the migrated database reads false", older.isTwoFactor());
        }

        migrate();   // a second start: nothing left to run
        assertEquals(0, column());
        assertEquals(1, scalar("SELECT count(*) FROM " + schema + ".databasechangelog WHERE id = '" + CHANGESET + "'"));
    }
}
