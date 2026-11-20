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
        asAdmin(() -> {
            DeviceDAO devices = injector.getInstance(DeviceDAO.class);
            com.hmdm.persistence.domain.Device device = devices.getDeviceById(id);
            device.setNumber(after);
            devices.updateDevice(device);
        });
        assertHistory(before, 0);
        assertHistory(after, 1);
    }

    @Test
    public void anAgentEnrolledDeviceKeepsItsNumber() throws Exception {
        String number = "agent-" + UUID.randomUUID();
        int id = deviceWithHistory(number);
        sql("UPDATE " + schema + ".devices SET agentSecretHash = 'x' WHERE id = " + id);
        asAdmin(() -> {
            DeviceDAO devices = injector.getInstance(DeviceDAO.class);
            com.hmdm.persistence.domain.Device device = devices.getDeviceById(id);
            device.setNumber("renamed-" + UUID.randomUUID());
            try {
                devices.updateDevice(device);
                fail("an agent-enrolled device was renamed");
            } catch (DeviceDAO.AgentDeviceRenameException expected) {
                assertEquals("error.device.number.agent", DeviceDAO.AgentDeviceRenameException.ERROR_KEY);
            }
            // Its other fields still save.
            device = devices.getDeviceById(id);
            device.setDescription("front desk");
            devices.updateDevice(device);
        });
        assertEquals(1, scalar("SELECT count(*) FROM " + schema + ".devices WHERE id = " + id + " AND number = '"
                + number + "' AND description = 'front desk'"));
        assertHistory(number, 1);
    }

    @Test
    public void historyWritesForADeletedDeviceAreRefusedAsDeviceGone() throws Exception {
        String number = "deleted-" + UUID.randomUUID();
        int id = deviceWithHistory(number);
        sql("DELETE FROM " + schema + ".devices WHERE id = " + id);
        AgentCommandDAO agent = injector.getInstance(AgentCommandDAO.class);
        RolloutDAO rollouts = injector.getInstance(RolloutDAO.class);
        com.hmdm.persistence.domain.AgentCommand command = new com.hmdm.persistence.domain.AgentCommand();
        command.setDeviceNumber(number);
        command.setType("device.lock");
        command.setStatus("pending");
        command.setCreatedAt(0L);
        com.hmdm.persistence.domain.DeviceState state = new com.hmdm.persistence.domain.DeviceState();
        state.setDeviceNumber(number);
        state.setUpdatedAt(0L);
        com.hmdm.persistence.domain.DeviceLocation location = new com.hmdm.persistence.domain.DeviceLocation();
        location.setDeviceNumber(number);
        location.setLat(1.0);
        location.setLon(2.0);
        location.setCapturedAt(1L);
        Runnable[] writes = {
                () -> agent.insert(command),
                () -> rollouts.insertIfNotInFlight(command),
                () -> agent.insertEvent(number, "boot", 0L, null),
                () -> agent.recordKioskReturn(number, 0L, null),
                () -> agent.upsertState(state),
                () -> agent.recordLocation(location)};
        for (int i = 0; i < writes.length; i++) {
            try {
                writes[i].run();
                fail("write " + i + " stored a row for a deleted device");
            } catch (DeviceGoneException expected) {
                assertEquals(number, expected.deviceNumber);
            }
        }
        assertHistory(number, 0);
    }

    @Test
    public void aCheckInRacingADeleteIsAnsweredAsAnUnknownDevice() throws Exception {
        for (boolean withState : new boolean[]{false, true}) {
            String number = "racing-" + UUID.randomUUID();
            int id = deviceWithHistory(number);
            String secret = "secret-" + UUID.randomUUID();
            sql("UPDATE " + schema + ".devices SET agentSecretHash = '"
                    + com.hmdm.util.CryptoUtil.getSHA256String(secret) + "' WHERE id = " + id);
            // The device is deleted once the check-in has found and authenticated it, before it writes anything.
            AgentCommandDAO agent = new AgentCommandDAO(
                    injector.getInstance(com.hmdm.persistence.mapper.AgentCommandMapper.class),
                    injector.getInstance(com.hmdm.persistence.mapper.AgentDeviceMapper.class),
                    injector.getInstance(com.hmdm.persistence.mapper.DeviceStateMapper.class),
                    injector.getInstance(com.hmdm.persistence.mapper.DeviceEventMapper.class)) {
                @Override
                public void touchLastUpdate(String deviceNumber) {
                    try {
                        sql("DELETE FROM " + schema + ".devices WHERE id = " + id);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    super.touchLastUpdate(deviceNumber);
                }
            };
            com.hmdm.rest.resource.AgentResource resource = new com.hmdm.rest.resource.AgentResource(
                    injector.getInstance(UnsecureDAO.class), null, agent, null, null, null, null, null, null, null);
            com.hmdm.rest.json.agent.AgentCheckInRequest request = new com.hmdm.rest.json.agent.AgentCheckInRequest();
            request.setDeviceId(number);
            if (withState) {
                request.setState(new com.hmdm.rest.json.agent.AgentDeviceState());
            }
            com.hmdm.rest.json.agent.AgentTelemetryEvent event = new com.hmdm.rest.json.agent.AgentTelemetryEvent();
            event.setType("boot");
            event.setTs(1L);
            request.setEvents(Collections.singletonList(event));

            com.hmdm.rest.json.Response response = resource.checkin("Bearer " + secret, null, request);

            assertEquals((withState ? "state" : "event") + " write", "error.agent.unauthorized",
                    response.getMessage());
            assertHistory(number, 0);
        }
    }

    @Test
    public void aBulkDeleteDeletesAllOrNothing() throws Exception {
        String a = "bulk-a-" + UUID.randomUUID();
        String b = "bulk-b-" + UUID.randomUUID();
        int idA = deviceWithHistory(a);
        int idB = deviceWithHistory(b);
        int mine = customerId;
        int myAdmin = adminId;
        setUp(); // another customer's device, out of the first admin's reach
        String foreign = "bulk-foreign-" + UUID.randomUUID();
        int idForeign = deviceWithHistory(foreign);

        SecurityContext.init(Fakes.user(myAdmin, mine, "edit_devices", "devices"));
        try {
            injector.getInstance(DeviceDAO.class).removeDevicesByIds(java.util.Arrays.asList(idA, idForeign, idB));
            fail("deleted another customer's device");
        } catch (RuntimeException expected) {
            // a security violation, after idA was deleted in the same transaction
        } finally {
            SecurityContext.release();
        }
        assertHistory(a, 1);
        assertHistory(b, 1);
        assertHistory(foreign, 1);
        assertEquals(3, scalar("SELECT count(*) FROM " + schema + ".devices WHERE id IN (" + idA + ", " + idB + ", "
                + idForeign + ")"));

        // A device already gone counts as deleted: the others still go.
        SecurityContext.init(Fakes.user(adminId, customerId, "edit_devices", "devices"));
        try {
            injector.getInstance(DeviceDAO.class).removeDevicesByIds(java.util.Arrays.asList(idForeign, -1));
        } finally {
            SecurityContext.release();
        }
        assertHistory(foreign, 0);
    }

    private interface Body {
        void run() throws Exception;
    }

    private void asAdmin(Body body) throws Exception {
        SecurityContext.init(Fakes.user(adminId, customerId, "edit_devices", "devices"));
        try {
            body.run();
        } finally {
            SecurityContext.release();
        }
    }
}
