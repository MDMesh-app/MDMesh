/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.hmdm.guice.module.PersistenceModule;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A deleted device takes its agent history with it: commands, state, events, locations and rollout-canary membership,
 * all keyed by device number, each by a foreign key to devices(number). Otherwise a device later added with the same
 * number (the console, an enrollment, a legacy launcher's on-demand creation) shows the deleted device's history as its
 * own, and in a multi-tenant install that can be another customer's. Deleting a customer does the same for its
 * devices; renaming a device carries its history; no row can name a number no device has. Real schema, real DAOs, as
 * DeviceLimitPathsTest.
 *
 * <p>Runs when MDMESH_TEST_PG_URL is set (see AgentEnrollmentTransactionTest for the docker one-liner); with
 * MDMESH_TEST_PG_REQUIRED it fails without one. Works in its own schema, dropped afterwards.</p>
 */
public class DeviceHistoryOnDeleteTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");

    /** The agent tables keyed by device number. */
    private static final String[] HISTORY = {"agentCommand", "device_state", "device_event", "device_location",
            "agentRolloutCanary"};

    private static String schema;
    private static Injector injector;

    private int customerId;
    private int configurationId;
    private int adminId;

    @BeforeClass
    public static void setUpClass() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        schema = "history_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    @Before
    public void setUp() throws Exception {
        String name = "c" + UUID.randomUUID().toString().substring(0, 8);
        customerId = (int) scalar("INSERT INTO " + schema + ".customers (name, filesDir, prefix) VALUES ('" + name
                + "', '" + name + "', '" + name + "') RETURNING id");
        configurationId = (int) scalar("INSERT INTO " + schema + ".configurations (name, customerId) VALUES ('"
                + name + "', " + customerId + ") RETURNING id");
        adminId = (int) scalar("INSERT INTO " + schema + ".users (login, email, name, password, customerId, userRoleId, "
                + "allDevicesAvailable, allConfigAvailable) VALUES ('" + name + "', '" + name + "@example.test', '"
                + name + "', 'x', " + customerId + ", 2, true, true) RETURNING id");
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

    /** A device of the customer with a row in every history table. @return its id. */
    private int deviceWithHistory(String number) throws Exception {
        int id = (int) scalar("INSERT INTO " + schema + ".devices (number, customerId, configurationId, lastUpdate) "
                + "VALUES ('" + number + "', " + customerId + ", " + configurationId + ", 0) RETURNING id");
        int rollout = (int) scalar("INSERT INTO " + schema + ".agentRollout (customerId, targetVersion, packageName, "
                + "apkUrl, stage, createdAt, updatedAt) VALUES (" + customerId + ", '1.0.0', 'p', 'u', 'cancelled', 0, 0) "
                + "RETURNING id");
        sql("INSERT INTO " + schema + ".agentCommand (deviceNumber, type, status, createdAt) VALUES ('" + number
                        + "', 'device.lock', 'done', 0)",
                "INSERT INTO " + schema + ".device_state (deviceNumber, updatedAt, agentVersion) VALUES ('" + number
                        + "', 0, '1.0.0')",
                "INSERT INTO " + schema + ".device_event (deviceNumber, type, ts) VALUES ('" + number + "', 'boot', 0)",
                "INSERT INTO " + schema + ".device_location (deviceNumber, lat, lon, capturedAt, recordedAt) VALUES ('"
                        + number + "', 1, 2, 0, 0)",
                "INSERT INTO " + schema + ".agentRolloutCanary (rolloutId, deviceNumber) VALUES (" + rollout + ", '"
                        + number + "')");
        return id;
    }

    private void assertHistory(String number, long rowsEach) throws Exception {
        for (String table : HISTORY) {
            assertEquals(table + " rows of " + number, rowsEach,
                    scalar("SELECT count(*) FROM " + schema + "." + table + " WHERE deviceNumber = '" + number + "'"));
        }
    }

    @Test
    public void deletingADeviceDeletesItsHistoryAndNoOtherDevices() throws Exception {
        String gone = "gone-" + UUID.randomUUID();
        String kept = "kept-" + UUID.randomUUID();
        int id = deviceWithHistory(gone);
        deviceWithHistory(kept);
        assertHistory(gone, 1);

        SecurityContext.init(Fakes.user(adminId, customerId, "edit_devices", "devices"));
        try {
            injector.getInstance(DeviceDAO.class).removeDeviceById(id);
        } finally {
            SecurityContext.release();
        }

        assertEquals(0, scalar("SELECT count(*) FROM " + schema + ".devices WHERE id = " + id));
        assertHistory(gone, 0);
        assertHistory(kept, 1);

        // The number added again starts with nothing of the deleted device's.
        sql("INSERT INTO " + schema + ".devices (number, customerId, configurationId, lastUpdate) VALUES ('" + gone
                + "', " + customerId + ", " + configurationId + ", 0)");
        assertHistory(gone, 0);
    }

    @Test
    public void deletingACustomerDeletesItsDevicesHistory() throws Exception {
        String a = "a-" + UUID.randomUUID();
        String b = "b-" + UUID.randomUUID();
        deviceWithHistory(a);
        deviceWithHistory(b);
        int doomed = customerId;

        setUp(); // another customer, whose device stays
        String other = "other-" + UUID.randomUUID();
        deviceWithHistory(other);

        SecurityContext.init(Fakes.superAdmin(1, 1));
        try {
            injector.getInstance(CustomerDAO.class).removeCustomerById(doomed);
        } finally {
            SecurityContext.release();
        }

        assertEquals(0, scalar("SELECT count(*) FROM " + schema + ".customers WHERE id = " + doomed));
        assertHistory(a, 0);
        assertHistory(b, 0);
        assertHistory(other, 1);
    }

    @Test
    public void aDeviceThatWasNotDeletedKeepsItsHistory() throws Exception {
        // Refused (not accessible to the user): nothing is deleted.
        String number = "foreign-" + UUID.randomUUID();
        int id = deviceWithHistory(number);
        SecurityContext.init(Fakes.user(adminId, customerId + 100000, "edit_devices", "devices"));
        try {
            injector.getInstance(DeviceDAO.class).removeDeviceById(id);
            fail("another customer's device was deleted");
        } catch (RuntimeException expected) {
            // a security violation
        } finally {
            SecurityContext.release();
        }
        assertEquals(1, scalar("SELECT count(*) FROM " + schema + ".devices WHERE id = " + id));
        assertHistory(number, 1);
    }

    @Test
    public void aNumberReusedAfterADeleteStartsWithNoHistory() throws Exception {
        String consoleNumber = "console-" + UUID.randomUUID();
        String tokenNumber = "token-" + UUID.randomUUID();
        int consoleId = deviceWithHistory(consoleNumber);
        int tokenId = deviceWithHistory(tokenNumber);

        SecurityContext.init(Fakes.user(adminId, customerId, "edit_devices", "devices"));
        try {
            DeviceDAO devices = injector.getInstance(DeviceDAO.class);
            devices.removeDeviceById(consoleId);
            devices.removeDeviceById(tokenId);

            // The console's add, and the device factory every enrollment uses: both insert through
            // DeviceMapper.insertDevice, as on-demand creation and a new customer's default devices do.
            com.hmdm.persistence.domain.Device device = new com.hmdm.persistence.domain.Device();
            device.setNumber(consoleNumber);
            device.setCustomerId(customerId);
            device.setConfigurationId(configurationId);
            device.setLastUpdate(0L);
            devices.insertDevice(device);
        } finally {
            SecurityContext.release();
        }
        assertTrue("enrollment's device factory made the device",
                injector.getInstance(UnsecureDAO.class).createNewDeviceForToken(tokenNumber, customerId, configurationId)
                        != null);

        AgentCommandDAO agent = injector.getInstance(AgentCommandDAO.class);
        for (String number : new String[]{consoleNumber, tokenNumber}) {
            assertHistory(number, 0);
            assertEquals(number + " commands", 0, agent.listHistory(number, 0, 100).size());
            assertEquals(number + " events", 0, agent.listEvents(number, 0, 100).size());
            assertEquals(number + " locations", 0, agent.listLocations(number, 0, 100).size());
            assertNull(number + " state", agent.getState(number));
        }
    }

    @Test
    public void noHistoryRowCanNameANumberNoDeviceHas() throws Exception {
        String nobody = "nobody-" + UUID.randomUUID();
        int rollout = (int) scalar("INSERT INTO " + schema + ".agentRollout (customerId, targetVersion, packageName, "
                + "apkUrl, stage, createdAt, updatedAt) VALUES (" + customerId + ", '1.0.0', 'p', 'u', 'cancelled', 0, 0) "
                + "RETURNING id");
        String[] inserts = {
                "INSERT INTO " + schema + ".agentCommand (deviceNumber, type, status, createdAt) VALUES ('" + nobody
                        + "', 'device.lock', 'pending', 0)",
                "INSERT INTO " + schema + ".device_state (deviceNumber, updatedAt) VALUES ('" + nobody + "', 0)",
                "INSERT INTO " + schema + ".device_event (deviceNumber, type, ts) VALUES ('" + nobody + "', 'boot', 0)",
                "INSERT INTO " + schema + ".device_location (deviceNumber, lat, lon, capturedAt, recordedAt) VALUES ('"
                        + nobody + "', 1, 2, 0, 0)",
                "INSERT INTO " + schema + ".agentRolloutCanary (rolloutId, deviceNumber) VALUES (" + rollout + ", '"
                        + nobody + "')"};
        for (String insert : inserts) {
            try {
                sql(insert);
                fail("stored a row for a number no device has: " + insert);
            } catch (SQLException e) {
                assertEquals(insert, "23503", e.getSQLState()); // foreign_key_violation
            }
        }
        // The rollout's canary insert skips such a number instead of failing the rollout.
        injector.getInstance(RolloutDAO.class).insertCanary(rollout, nobody);
        assertHistory(nobody, 0);
    }

    @Test
    public void renamingADeviceCarriesItsHistory() throws Exception {
        String before = "before-" + UUID.randomUUID();
        String after = "after-" + UUID.randomUUID();
        int id = deviceWithHistory(before);
        sql("UPDATE " + schema + ".devices SET number = '" + after + "' WHERE id = " + id);
        assertHistory(before, 0);
        assertHistory(after, 1);
    }
}
